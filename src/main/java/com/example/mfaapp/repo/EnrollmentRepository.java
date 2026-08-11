package com.example.mfaapp.repo;

import com.example.mfaapp.domain.Enrollment;
import com.example.mfaapp.domain.EnrollmentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    Optional<Enrollment> findByUserIdAndModuleId(Long userId, Long moduleId);

    Optional<Enrollment> findByUserIdAndModuleSlug(Long userId, String slug);

    /** One row per status for the caller — the whole summary tile row in a single statement. */
    @Query("""
            select e.status, count(e.id) from Enrollment e
            where e.user.id = :userId
            group by e.status
            """)
    List<Object[]> countByStatus(@Param("userId") Long userId);

    /**
     * Slug of the most recently viewed in-progress module. Returns a list so the caller can apply a
     * limit; take the first element.
     */
    @Query("""
            select m.slug from Enrollment e
            join e.module m
            where e.user.id = :userId and e.status = :status
            order by e.lastViewedAt desc, e.id desc
            """)
    List<String> findSlugsByStatusMostRecentlyViewed(@Param("userId") Long userId,
                                                     @Param("status") EnrollmentStatus status,
                                                     Pageable limit);
}
