package com.example.mfaapp.domain;

/**
 * Enrollment lifecycle. {@link #NOT_ENROLLED} is a catalog-facing pseudo status: it is never
 * persisted, it is what the caller sees for a module they have no {@link Enrollment} row for.
 */
public enum EnrollmentStatus {
    NOT_ENROLLED,
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED;

    /** True for the values that can actually appear in the {@code enrollment.status} column. */
    public boolean isPersistable() {
        return this != NOT_ENROLLED;
    }
}
