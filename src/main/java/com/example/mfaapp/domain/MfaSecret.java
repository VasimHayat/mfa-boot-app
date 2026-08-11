package com.example.mfaapp.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A user's TOTP enrolment. Holds the AES-GCM encrypted shared secret, the single-use recovery
 * codes, the TOTP replay guard and the verify-attempt throttle counters.
 */
@Entity
@Table(name = "mfa_secret", uniqueConstraints = @UniqueConstraint(name = "uk_mfa_secret_user", columnNames = "user_id"))
public class MfaSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** AES-GCM ciphertext of the Base32 secret. Never plaintext. */
    @Column(name = "secret_ciphertext", nullable = false, length = 512)
    private String secretCiphertext;

    /** False until the user has proved possession by entering a valid code. */
    @Column(nullable = false)
    private boolean confirmed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /**
     * Highest TOTP time-step already accepted for this user. Codes at or below it are replays and
     * are rejected even when they are still inside the tolerance window.
     */
    @Column(name = "last_accepted_time_step")
    private Long lastAcceptedTimeStep;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    /** Start of the current rolling throttle window. */
    @Column(name = "attempt_window_started_at")
    private Instant attemptWindowStartedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mfa_recovery_code", joinColumns = @JoinColumn(name = "mfa_secret_id"))
    @OrderColumn(name = "position")
    private List<RecoveryCode> recoveryCodes = new ArrayList<>();

    protected MfaSecret() {
        // for JPA
    }

    public MfaSecret(User user, String secretCiphertext, Instant createdAt) {
        this.user = user;
        this.secretCiphertext = secretCiphertext;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Long getLastAcceptedTimeStep() {
        return lastAcceptedTimeStep;
    }

    public void setLastAcceptedTimeStep(Long lastAcceptedTimeStep) {
        this.lastAcceptedTimeStep = lastAcceptedTimeStep;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getAttemptWindowStartedAt() {
        return attemptWindowStartedAt;
    }

    public void setAttemptWindowStartedAt(Instant attemptWindowStartedAt) {
        this.attemptWindowStartedAt = attemptWindowStartedAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public List<RecoveryCode> getRecoveryCodes() {
        return Collections.unmodifiableList(recoveryCodes);
    }

    /**
     * Rotates the secret while it is still unconfirmed, so a user who abandons setup and starts
     * over gets a fresh secret instead of being stuck with the first one.
     */
    public void replaceUnconfirmedSecret(String newCiphertext, Instant at) {
        if (confirmed) {
            throw new IllegalStateException("Cannot replace a confirmed MFA secret");
        }
        this.secretCiphertext = newCiphertext;
        this.createdAt = at;
    }

    public void confirm(Instant at) {
        this.confirmed = true;
        this.confirmedAt = at;
    }

    public void replaceRecoveryCodes(List<String> hashes) {
        this.recoveryCodes.clear();
        hashes.forEach(h -> this.recoveryCodes.add(new RecoveryCode(h)));
    }

    /** Mutable view, used only to flip a matched code to used. */
    public List<RecoveryCode> mutableRecoveryCodes() {
        return recoveryCodes;
    }

    public long unusedRecoveryCodeCount() {
        return recoveryCodes.stream().filter(c -> !c.isUsed()).count();
    }
}
