package com.example.mfaapp.web.dto;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.EnrollmentStatus;
import com.example.mfaapp.domain.ModuleCategory;

/**
 * Catalog card projection. {@code progressPercent} is always derived from
 * {@code completedLessons / lessonCount} — it is never read from a stored column.
 */
public record ModuleCardDto(
        Long id,
        String slug,
        String title,
        String summary,
        ModuleCategory category,
        Difficulty difficulty,
        int estimatedMinutes,
        String thumbnailUrl,
        long lessonCount,
        EnrollmentStatus enrollmentStatus,
        long completedLessons,
        int progressPercent) {

    public static ModuleCardDto of(Long id, String slug, String title, String summary,
                                   ModuleCategory category, Difficulty difficulty,
                                   int estimatedMinutes, String thumbnailUrl,
                                   long lessonCount, EnrollmentStatus enrollmentStatus,
                                   long completedLessons) {
        return new ModuleCardDto(id, slug, title, summary, category, difficulty, estimatedMinutes,
                thumbnailUrl, lessonCount,
                enrollmentStatus == null ? EnrollmentStatus.NOT_ENROLLED : enrollmentStatus,
                completedLessons, progressPercent(completedLessons, lessonCount));
    }

    public static int progressPercent(long completedLessons, long lessonCount) {
        if (lessonCount <= 0) {
            return 0;
        }
        long capped = Math.min(completedLessons, lessonCount);
        return (int) Math.round(capped * 100.0 / lessonCount);
    }
}
