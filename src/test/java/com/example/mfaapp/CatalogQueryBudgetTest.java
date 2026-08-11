package com.example.mfaapp;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.Enrollment;
import com.example.mfaapp.domain.Lesson;
import com.example.mfaapp.domain.LessonProgress;
import com.example.mfaapp.domain.Module;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.CatalogFilter;
import com.example.mfaapp.repo.CatalogSort;
import com.example.mfaapp.service.LearningService;
import com.example.mfaapp.support.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog must cost the same number of round trips whether it returns one card or forty.
 *
 * <p>A projection that pulled lesson counts or enrollments per row would pass every functional test
 * and still melt under a realistic page size, so the statement count is asserted directly via
 * Hibernate statistics rather than inferred.
 */
class CatalogQueryBudgetTest extends IntegrationTestBase {

    /** One COUNT for the page metadata plus one SELECT for the rows. */
    private static final long EXPECTED_STATEMENTS_PER_CATALOG_PAGE = 2;

    @Autowired
    private LearningService learningService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private User learner;

    @BeforeEach
    void seedLargeCatalog() {
        learner = newUser("counter", Role.USER);

        for (int i = 1; i <= 40; i++) {
            Module module = newModule("module-" + String.format("%02d", i), "Module " + i,
                    ModuleCategory.values()[i % ModuleCategory.values().length],
                    Difficulty.values()[i % Difficulty.values().length],
                    4 + (i % 5), null, i);
            // Enrol in every third module, and complete some of its lessons, so the projection has
            // real enrollment and progress data to resolve rather than only nulls.
            if (i % 3 == 0) {
                Enrollment enrollment = enrollments.save(new Enrollment(learner, module, clock.instant()));
                List<Lesson> lessons = module.getLessons();
                for (int l = 0; l < Math.min(2, lessons.size()); l++) {
                    LessonProgress progress = new LessonProgress(enrollment, lessons.get(l), clock.instant());
                    progress.markCompleted(clock.instant());
                    lessonProgress.save(progress);
                }
                enrollment.applyProgress(Math.min(2, lessons.size()), lessons.size(), clock.instant());
                enrollments.save(enrollment);
            }
        }
    }

    @Test
    @DisplayName("A catalog page costs exactly two statements, whatever the page size")
    void catalogStatementCountDoesNotGrowWithPageSize() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled())
                .as("hibernate.generate_statistics must be on for this assertion to mean anything")
                .isTrue();

        long forOneRow = statementsFor(1);
        long forTwelveRows = statementsFor(12);
        long forFortyRows = statementsFor(40);

        assertThat(forOneRow).isEqualTo(EXPECTED_STATEMENTS_PER_CATALOG_PAGE);
        assertThat(forTwelveRows).isEqualTo(EXPECTED_STATEMENTS_PER_CATALOG_PAGE);
        assertThat(forFortyRows).isEqualTo(EXPECTED_STATEMENTS_PER_CATALOG_PAGE);
    }

    @Test
    @DisplayName("The statement count is unchanged when every filter is engaged")
    void filteredCatalogAlsoCostsTwoStatements() {
        CatalogFilter filter = new CatalogFilter(
                EnumSet.of(ModuleCategory.SECURITY, ModuleCategory.ENGINEERING),
                Difficulty.BEGINNER,
                EnumSet.of(com.example.mfaapp.domain.EnrollmentStatus.NOT_ENROLLED,
                        com.example.mfaapp.domain.EnrollmentStatus.IN_PROGRESS),
                "module",
                CatalogSort.NEWEST);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        Page<?> page = learningService.catalog(learner, filter, PageRequest.of(0, 40));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(EXPECTED_STATEMENTS_PER_CATALOG_PAGE);
        assertThat(page.getContent()).isNotEmpty();
    }

    private long statementsFor(int pageSize) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        Page<?> page = learningService.catalog(learner, CatalogFilter.none(), PageRequest.of(0, pageSize));
        long count = statistics.getPrepareStatementCount();
        assertThat(page.getContent()).hasSize(pageSize);
        assertThat(page.getTotalElements()).isEqualTo(40);
        return count;
    }
}
