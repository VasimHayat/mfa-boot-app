package com.example.mfaapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A user's enrollment in a module.
 *
 * <p>Deliberately carries no percentage column: progress is always derived from
 * {@link LessonProgress} rows so it cannot drift out of sync with the lessons.
 */
@Entity
@Table(name = "enrollment",
        uniqueConstraints = @UniqueConstraint(name = "uk_enrollment_user_module",
                columnNames = {"user_id", "module_id"}))
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    private Module module;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EnrollmentStatus status = EnrollmentStatus.NOT_STARTED;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Drives "continue learning". A recorded fact, not derived state. */
    @Column(name = "last_viewed_at")
    private Instant lastViewedAt;

    protected Enrollment() {
        // for JPA
    }

    public Enrollment(User user, Module module, Instant enrolledAt) {
        this.user = user;
        this.module = module;
        this.enrolledAt = enrolledAt;
        this.lastViewedAt = enrolledAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Module getModule() {
        return module;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public EnrollmentStatus getStatus() {
        return status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getLastViewedAt() {
        return lastViewedAt;
    }

    public void touchViewed(Instant at) {
        this.lastViewedAt = at;
    }

    /**
     * Moves the enrollment to the status implied by lesson completion counts.
     * {@code completedAt} is stamped on the transition into COMPLETED and cleared if the module
     * later gains lessons and is no longer finished.
     */
    public void applyProgress(long completedLessons, long totalLessons, Instant at) {
        if (totalLessons > 0 && completedLessons >= totalLessons) {
            if (this.status != EnrollmentStatus.COMPLETED) {
                this.status = EnrollmentStatus.COMPLETED;
                this.completedAt = at;
            }
        } else if (completedLessons > 0) {
            this.status = EnrollmentStatus.IN_PROGRESS;
            this.completedAt = null;
        } else {
            this.status = EnrollmentStatus.NOT_STARTED;
            this.completedAt = null;
        }
    }
}
