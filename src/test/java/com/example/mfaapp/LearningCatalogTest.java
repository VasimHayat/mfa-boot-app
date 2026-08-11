package com.example.mfaapp;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.Lesson;
import com.example.mfaapp.domain.Module;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog filtering, enrollment and progress. Authentication is stubbed with {@code @WithMockUser}
 * so each test exercises the learning rules rather than re-running the MFA flow; the rule that a
 * pre-auth session cannot reach these endpoints is covered by its own test below.
 */
@WithMockUser(username = LearningCatalogTest.LEARNER, roles = "USER")
class LearningCatalogTest extends IntegrationTestBase {

    static final String LEARNER = "learner";

    private User learner;

    @BeforeEach
    void seedCatalog() {
        learner = newUser(LEARNER, Role.USER);

        newModule("sec-beginner", "Phishing Basics", ModuleCategory.SECURITY, Difficulty.BEGINNER, 4, null, 1);
        newModule("sec-advanced", "Incident Command", ModuleCategory.SECURITY, Difficulty.ADVANCED, 5, null, 2);
        newModule("eng-inter", "Idempotent APIs", ModuleCategory.ENGINEERING, Difficulty.INTERMEDIATE, 6, null, 3);
        newModule("eng-beginner", "Reading Query Plans", ModuleCategory.ENGINEERING, Difficulty.BEGINNER, 3, null, 4);
        newModule("comp-inter", "Data Retention", ModuleCategory.COMPLIANCE, Difficulty.INTERMEDIATE, 4, null, 5);
        newModule("onb-beginner", "Your First Week", ModuleCategory.ONBOARDING, Difficulty.BEGINNER, 5, null, 6);
        newModule("prod-beginner", "Roadmap Reading", ModuleCategory.PRODUCT, Difficulty.BEGINNER, 4, null, 7);
        // Only visible to a COMPLIANCE_OFFICER, which `learner` is not.
        newModule("comp-gated", "Audit Evidence Handling", ModuleCategory.COMPLIANCE, Difficulty.ADVANCED, 3,
                Role.COMPLIANCE_OFFICER, 8);
    }

    @Test
    @DisplayName("Filters compose with AND and pagination metadata is accurate")
    void filtersComposeAndPaginationIsAccurate() throws Exception {
        // No filters: 7 visible modules (the role-gated one is excluded).
        mockMvc.perform(get("/api/modules").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/modules").param("size", "3").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.last").value(true));

        // Single filter.
        mockMvc.perform(get("/api/modules").param("category", "SECURITY"))
                .andExpect(jsonPath("$.totalElements").value(2));

        // Two categories OR within the filter.
        mockMvc.perform(get("/api/modules").param("category", "SECURITY", "ENGINEERING"))
                .andExpect(jsonPath("$.totalElements").value(4));

        // category AND difficulty.
        mockMvc.perform(get("/api/modules")
                        .param("category", "SECURITY", "ENGINEERING")
                        .param("difficulty", "BEGINNER"))
                .andExpect(jsonPath("$.totalElements").value(2));

        // ...AND a case-insensitive text match on title or summary.
        mockMvc.perform(get("/api/modules")
                        .param("category", "SECURITY", "ENGINEERING")
                        .param("difficulty", "BEGINNER")
                        .param("q", "PHISHING"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("sec-beginner"));

        // q also matches the summary text, not just the title.
        mockMvc.perform(get("/api/modules").param("q", "query plans summary"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("eng-beginner"));

        // A filter combination with no members returns an accurate empty page.
        mockMvc.perform(get("/api/modules").param("category", "PRODUCT").param("difficulty", "ADVANCED"))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("The status filter is evaluated against the caller's own enrollment, including NOT_ENROLLED")
    void statusFilterUsesCallerEnrollment() throws Exception {
        enroll("eng-inter");
        completeLesson("eng-inter", lessonAt("eng-inter", 0));
        enroll("sec-beginner");
        completeAllLessons("sec-advanced");

        mockMvc.perform(get("/api/modules").param("status", "IN_PROGRESS"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("eng-inter"))
                .andExpect(jsonPath("$.content[0].completedLessons").value(1))
                .andExpect(jsonPath("$.content[0].lessonCount").value(6))
                .andExpect(jsonPath("$.content[0].progressPercent").value(17));

        mockMvc.perform(get("/api/modules").param("status", "COMPLETED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("sec-advanced"))
                .andExpect(jsonPath("$.content[0].progressPercent").value(100));

        mockMvc.perform(get("/api/modules").param("status", "NOT_STARTED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("sec-beginner"));

        // Four of the seven visible modules were never enrolled in.
        mockMvc.perform(get("/api/modules").param("status", "NOT_ENROLLED"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].enrollmentStatus").value("NOT_ENROLLED"));

        // NOT_ENROLLED composes with the persisted statuses in one OR-set...
        mockMvc.perform(get("/api/modules").param("status", "NOT_ENROLLED", "NOT_STARTED"))
                .andExpect(jsonPath("$.totalElements").value(5));

        // ...and still ANDs with the other filters.
        mockMvc.perform(get("/api/modules").param("status", "NOT_ENROLLED").param("category", "ENGINEERING"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("eng-beginner"));
    }

    @Test
    @DisplayName("Sort options are honoured and unknown values fall back to the default order")
    void sortOptionsAreHonoured() throws Exception {
        mockMvc.perform(get("/api/modules").param("sort", "TITLE_ASC").param("size", "20"))
                .andExpect(jsonPath("$.content[0].title").value("Data Retention"))
                .andExpect(jsonPath("$.content[6].title").value("Your First Week"));

        mockMvc.perform(get("/api/modules").param("sort", "SHORTEST").param("size", "20"))
                .andExpect(jsonPath("$.content[0].slug").value("eng-beginner"));

        mockMvc.perform(get("/api/modules").param("sort", "not-a-sort").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("sec-beginner"));
    }

    @Test
    @DisplayName("A role-gated module is absent from the catalog and 404s by slug for a user without the role")
    void roleGatedModuleIsHidden() throws Exception {
        mockMvc.perform(get("/api/modules").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.slug == 'comp-gated')]").isEmpty());

        mockMvc.perform(get("/api/modules/comp-gated"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        // Enrolling in something you cannot see is also a 404, not a 403.
        mockMvc.perform(post("/api/modules/comp-gated/enroll").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "auditor", roles = {"USER", "COMPLIANCE_OFFICER"})
    @DisplayName("A user holding the required role sees the gated module")
    void roleGatedModuleIsVisibleToHolder() throws Exception {
        newUser("auditor", Role.USER, Role.COMPLIANCE_OFFICER);

        mockMvc.perform(get("/api/modules").param("size", "50"))
                .andExpect(jsonPath("$.totalElements").value(8));
        mockMvc.perform(get("/api/modules/comp-gated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("comp-gated"));
    }

    @Test
    @DisplayName("Enrolling twice is idempotent and creates exactly one row")
    void enrollingTwiceIsIdempotent() throws Exception {
        String first = mockMvc.perform(post("/api/modules/eng-inter/enroll").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/modules/eng-inter/enroll").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("enrollmentId"))
                .isEqualTo(objectMapper.readTree(first).get("enrollmentId"));
        assertThat(enrollments.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("Completing the last lesson flips the enrollment to COMPLETED and stamps completedAt")
    void completingTheFinalLessonCompletesTheEnrollment() throws Exception {
        List<Lesson> lessons = lessonsOf("eng-beginner");
        enroll("eng-beginner");

        mockMvc.perform(post("/api/modules/eng-beginner/lessons/" + lessons.get(0).getId() + "/complete")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedLessons").value(1))
                .andExpect(jsonPath("$.progressPercent").value(33))
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        mockMvc.perform(post("/api/modules/eng-beginner/lessons/" + lessons.get(1).getId() + "/complete")
                        .with(csrf()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        mockMvc.perform(post("/api/modules/eng-beginner/lessons/" + lessons.get(2).getId() + "/complete")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedLessons").value(3))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.completedAt").exists());

        var enrollment = enrollments.findByUserIdAndModuleSlug(learner.getId(), "eng-beginner").orElseThrow();
        assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.COMPLETED);
        assertThat(enrollment.getCompletedAt()).isNotNull();

        // Re-completing an already-finished lesson is a no-op, not a double count.
        mockMvc.perform(post("/api/modules/eng-beginner/lessons/" + lessons.get(2).getId() + "/complete")
                        .with(csrf()))
                .andExpect(jsonPath("$.completedLessons").value(3))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Completing a lesson that belongs to a different module returns 400")
    void completingAForeignLessonIsRejected() throws Exception {
        Long foreignLesson = lessonsOf("sec-beginner").get(0).getId();

        mockMvc.perform(post("/api/modules/eng-inter/lessons/" + foreignLesson + "/complete").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        mockMvc.perform(post("/api/modules/eng-inter/lessons/999999/complete").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("The detail page carries the ordered lessons with per-lesson completion flags")
    void detailReturnsOrderedLessonsWithFlags() throws Exception {
        List<Lesson> lessons = lessonsOf("eng-inter");
        enroll("eng-inter");
        completeLesson("eng-inter", lessons.get(1).getId());

        mockMvc.perform(get("/api/modules/eng-inter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons.length()").value(6))
                .andExpect(jsonPath("$.lessons[0].orderIndex").value(1))
                .andExpect(jsonPath("$.lessons[5].orderIndex").value(6))
                .andExpect(jsonPath("$.lessons[0].completed").value(false))
                .andExpect(jsonPath("$.lessons[1].completed").value(true))
                .andExpect(jsonPath("$.lessons[1].completedAt").exists())
                .andExpect(jsonPath("$.enrollmentStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedLessons").value(1))
                .andExpect(jsonPath("$.progressPercent").value(17));
    }

    @Test
    @DisplayName("The learning summary counts enrollments and offers the most recently viewed module to resume")
    void learningSummaryReflectsProgress() throws Exception {
        completeAllLessons("eng-beginner");          // COMPLETED, 3 lessons x 10 min
        enroll("sec-beginner");                      // NOT_STARTED
        enroll("eng-inter");
        completeLesson("eng-inter", lessonAt("eng-inter", 0));   // IN_PROGRESS, +10 min
        enroll("comp-inter");
        completeLesson("comp-inter", lessonAt("comp-inter", 0)); // IN_PROGRESS, +10 min, most recent

        mockMvc.perform(get("/api/me/learning/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolledCount").value(4))
                .andExpect(jsonPath("$.inProgressCount").value(2))
                .andExpect(jsonPath("$.completedCount").value(1))
                .andExpect(jsonPath("$.totalMinutesCompleted").value(50))
                .andExpect(jsonPath("$.continueLearning.slug").value("comp-inter"))
                .andExpect(jsonPath("$.continueLearning.progressPercent").value(25));
    }

    @Test
    @DisplayName("With nothing in progress there is no module to resume")
    void continueLearningIsNullWhenNothingIsInProgress() throws Exception {
        enroll("sec-beginner");

        mockMvc.perform(get("/api/me/learning/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolledCount").value(1))
                .andExpect(jsonPath("$.inProgressCount").value(0))
                .andExpect(jsonPath("$.continueLearning").doesNotExist());
    }

    @Test
    @DisplayName("An unparseable filter value is a 400, not a 500")
    void invalidFilterValueIsRejected() throws Exception {
        mockMvc.perform(get("/api/modules").param("difficulty", "EXPERT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"));
    }

    // --- helpers -------------------------------------------------------------------------------

    private void enroll(String slug) throws Exception {
        mockMvc.perform(post("/api/modules/" + slug + "/enroll").with(csrf()))
                .andExpect(status().isOk());
    }

    private void completeLesson(String slug, Long lessonId) throws Exception {
        mockMvc.perform(post("/api/modules/" + slug + "/lessons/" + lessonId + "/complete").with(csrf()))
                .andExpect(status().isOk());
    }

    private void completeAllLessons(String slug) throws Exception {
        enroll(slug);
        for (Lesson lesson : lessonsOf(slug)) {
            completeLesson(slug, lesson.getId());
        }
    }

    private Long lessonAt(String slug, int index) {
        return lessonsOf(slug).get(index).getId();
    }

    private List<Lesson> lessonsOf(String slug) {
        Module module = modules.findBySlugWithLessons(slug).orElseThrow();
        return module.getLessons().stream()
                .sorted(Comparator.comparingInt(Lesson::getOrderIndex))
                .toList();
    }
}
