package com.example.mfaapp.repo;

import com.example.mfaapp.domain.LessonProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LessonProgressRepository extends JpaRepository<LessonProgress, Long> {

    Optional<LessonProgress> findByEnrollmentIdAndLessonId(Long enrollmentId, Long lessonId);

    long countByEnrollmentIdAndCompletedTrue(Long enrollmentId);

    /**
     * {@code [lessonId, completedAt]} for every lesson the caller has completed in one enrollment.
     * One statement for the whole detail page, so per-lesson flags cost no extra queries.
     */
    @Query("""
            select p.lesson.id, p.completedAt from LessonProgress p
            where p.enrollment.id = :enrollmentId and p.completed = true
            """)
    List<Object[]> findCompletedLessonMarks(@Param("enrollmentId") Long enrollmentId);

    /** Minutes of learning the caller has actually finished, across every enrollment. */
    @Query("""
            select coalesce(sum(p.lesson.estimatedMinutes), 0) from LessonProgress p
            where p.enrollment.user.id = :userId and p.completed = true
            """)
    long sumCompletedMinutes(@Param("userId") Long userId);
}
