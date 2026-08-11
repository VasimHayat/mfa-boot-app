package com.example.mfaapp.web.dto;

import com.example.mfaapp.domain.ContentType;
import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.ModuleCategory;

import java.time.Instant;
import java.util.List;

/** Response payloads for the learning endpoints. */
public final class LearningDtos {

    private LearningDtos() {
    }

    public record LessonDto(
            Long id,
            String title,
            int orderIndex,
            ContentType contentType,
            String contentRef,
            int estimatedMinutes,
            boolean completed,
            Instant completedAt) {
    }

    public record ModuleDetailDto(
            Long id,
            String slug,
            String title,
            String summary,
            String description,
            ModuleCategory category,
            Difficulty difficulty,
            int estimatedMinutes,
            String thumbnailUrl,
            EnrollmentStatus enrollmentStatus,
            Instant enrolledAt,
            Instant completedAt,
            long lessonCount,
            long completedLessons,
            int progressPercent,
            List<LessonDto> lessons) {
    }

    /** Returned by enroll and by lesson completion. */
    public record EnrollmentDto(
            Long enrollmentId,
            String moduleSlug,
            EnrollmentStatus status,
            Instant enrolledAt,
            Instant completedAt,
            long lessonCount,
            long completedLessons,
            int progressPercent) {
    }

    public record LearningSummaryDto(
            long enrolledCount,
            long inProgressCount,
            long completedCount,
            long totalMinutesCompleted,
            ModuleCardDto continueLearning) {
    }
}
