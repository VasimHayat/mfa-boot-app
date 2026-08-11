package com.example.mfaapp.service;

import com.example.mfaapp.domain.Enrollment;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.Lesson;
import com.example.mfaapp.domain.LessonProgress;
import com.example.mfaapp.domain.Module;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.CatalogFilter;
import com.example.mfaapp.repo.EnrollmentRepository;
import com.example.mfaapp.repo.LessonProgressRepository;
import com.example.mfaapp.repo.ModuleRepository;
import com.example.mfaapp.repo.UserRepository;
import com.example.mfaapp.web.dto.LearningDtos.EnrollmentDto;
import com.example.mfaapp.web.dto.LearningDtos.LearningSummaryDto;
import com.example.mfaapp.web.dto.LearningDtos.LessonDto;
import com.example.mfaapp.web.dto.LearningDtos.ModuleDetailDto;
import com.example.mfaapp.web.dto.ModuleCardDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Catalog reads, enrollment and progress tracking. */
@Service
public class LearningService {

    private final ModuleRepository modules;
    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository lessonProgress;
    private final UserRepository users;
    private final Clock clock;

    public LearningService(ModuleRepository modules, EnrollmentRepository enrollments,
                           LessonProgressRepository lessonProgress, UserRepository users, Clock clock) {
        this.modules = modules;
        this.enrollments = enrollments;
        this.lessonProgress = lessonProgress;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Callers hand us a {@code User} loaded in an earlier transaction, so it is detached by the time
     * we write. New rows must point at a reference managed by the current persistence context.
     */
    private User managed(User user) {
        return users.getReferenceById(user.getId());
    }

    @Transactional(readOnly = true)
    public Page<ModuleCardDto> catalog(User user, CatalogFilter filter, Pageable pageable) {
        return modules.findCatalog(user.getId(), user.getRoles(), filter, pageable);
    }

    @Transactional
    public ModuleDetailDto detail(User user, String slug) {
        Module module = requireVisible(user, slug);
        Optional<Enrollment> enrollment = enrollments.findByUserIdAndModuleId(user.getId(), module.getId());

        // One statement for every per-lesson completion flag and timestamp on the page.
        Map<Long, Instant> completedMarks = new HashMap<>();
        enrollment.ifPresent(e -> lessonProgress.findCompletedLessonMarks(e.getId())
                .forEach(row -> completedMarks.put((Long) row[0], (Instant) row[1])));
        // Opening the detail page is what makes a module the "continue learning" candidate.
        enrollment.ifPresent(e -> e.touchViewed(clock.instant()));

        List<Lesson> ordered = orderedLessons(module);
        List<LessonDto> lessons = ordered.stream()
                .map(lesson -> new LessonDto(lesson.getId(), lesson.getTitle(), lesson.getOrderIndex(),
                        lesson.getContentType(), lesson.getContentRef(), lesson.getEstimatedMinutes(),
                        completedMarks.containsKey(lesson.getId()), completedMarks.get(lesson.getId())))
                .toList();

        long lessonCount = ordered.size();
        long completed = completedMarks.size();
        return new ModuleDetailDto(module.getId(), module.getSlug(), module.getTitle(), module.getSummary(),
                module.getDescription(), module.getCategory(), module.getDifficulty(),
                module.getEstimatedMinutes(), module.getThumbnailUrl(),
                enrollment.map(Enrollment::getStatus).orElse(EnrollmentStatus.NOT_ENROLLED),
                enrollment.map(Enrollment::getEnrolledAt).orElse(null),
                enrollment.map(Enrollment::getCompletedAt).orElse(null),
                lessonCount, completed, ModuleCardDto.progressPercent(completed, lessonCount), lessons);
    }

    /**
     * Idempotent. Re-enrolling returns the existing enrollment untouched, so the endpoint never has
     * to answer 409. The unique constraint on (user, module) is the backstop for the rare concurrent
     * double-submit.
     */
    @Transactional
    public EnrollmentDto enroll(User user, String slug) {
        Module module = requireVisible(user, slug);
        Enrollment enrollment = enrollments.findByUserIdAndModuleId(user.getId(), module.getId())
                .orElseGet(() -> enrollments.save(new Enrollment(managed(user), module, clock.instant())));
        long completed = lessonProgress.countByEnrollmentIdAndCompletedTrue(enrollment.getId());
        return toEnrollmentDto(enrollment, module, completed, orderedLessons(module).size());
    }

    /**
     * Marks a lesson complete, then recomputes the enrollment status from the lesson counts.
     *
     * @throws InvalidRequestException if the lesson does not belong to {@code slug}
     */
    @Transactional
    public EnrollmentDto completeLesson(User user, String slug, Long lessonId) {
        Module module = requireVisible(user, slug);
        List<Lesson> ordered = orderedLessons(module);
        Lesson lesson = ordered.stream()
                .filter(candidate -> candidate.getId().equals(lessonId))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "Lesson " + lessonId + " does not belong to module " + slug));

        Instant now = clock.instant();
        Enrollment enrollment = enrollments.findByUserIdAndModuleId(user.getId(), module.getId())
                .orElseGet(() -> enrollments.save(new Enrollment(managed(user), module, now)));

        LessonProgress progress = lessonProgress
                .findByEnrollmentIdAndLessonId(enrollment.getId(), lesson.getId())
                .orElseGet(() -> new LessonProgress(enrollment, lesson, now));
        progress.markCompleted(now);
        lessonProgress.saveAndFlush(progress);

        long completed = lessonProgress.countByEnrollmentIdAndCompletedTrue(enrollment.getId());
        enrollment.applyProgress(completed, ordered.size(), now);
        enrollment.touchViewed(now);
        enrollments.save(enrollment);

        return toEnrollmentDto(enrollment, module, completed, ordered.size());
    }

    @Transactional(readOnly = true)
    public LearningSummaryDto summary(User user) {
        Map<EnrollmentStatus, Long> byStatus = new EnumMap<>(EnrollmentStatus.class);
        for (Object[] row : enrollments.countByStatus(user.getId())) {
            byStatus.put((EnrollmentStatus) row[0], (Long) row[1]);
        }
        long enrolled = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long inProgress = byStatus.getOrDefault(EnrollmentStatus.IN_PROGRESS, 0L);
        long completed = byStatus.getOrDefault(EnrollmentStatus.COMPLETED, 0L);
        long minutes = lessonProgress.sumCompletedMinutes(user.getId());

        ModuleCardDto continueLearning = enrollments
                .findSlugsByStatusMostRecentlyViewed(user.getId(), EnrollmentStatus.IN_PROGRESS,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .flatMap(slug -> modules.findCard(user.getId(), user.getRoles(), slug))
                .orElse(null);

        return new LearningSummaryDto(enrolled, inProgress, completed, minutes, continueLearning);
    }

    private Module requireVisible(User user, String slug) {
        Module module = modules.findBySlugWithLessons(slug)
                .orElseThrow(() -> new ResourceNotFoundException("No module with slug " + slug));
        Set<Role> roles = user.getRoles();
        if (!module.isVisibleTo(roles)) {
            throw new ResourceNotFoundException("No module with slug " + slug);
        }
        return module;
    }

    private static List<Lesson> orderedLessons(Module module) {
        return module.getLessons().stream()
                .sorted(Comparator.comparingInt(Lesson::getOrderIndex))
                .toList();
    }

    private static EnrollmentDto toEnrollmentDto(Enrollment enrollment, Module module,
                                                 long completedLessons, long lessonCount) {
        return new EnrollmentDto(enrollment.getId(), module.getSlug(), enrollment.getStatus(),
                enrollment.getEnrolledAt(), enrollment.getCompletedAt(), lessonCount, completedLessons,
                ModuleCardDto.progressPercent(completedLessons, lessonCount));
    }
}
