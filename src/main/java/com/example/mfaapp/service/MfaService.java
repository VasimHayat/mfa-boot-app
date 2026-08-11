package com.example.mfaapp.service;

import com.example.mfaapp.config.MfaProperties;
import com.example.mfaapp.domain.MfaSecret;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.MfaSecretRepository;
import com.example.mfaapp.web.dto.AuthDtos.MfaSetupResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Enrolment and challenge logic for TOTP MFA: secret issue/confirm, code verification with the
 * replay guard, recovery-code redemption, and the failed-attempt throttle.
 *
 * <p>Outcomes are returned as values rather than thrown, because a failed attempt must still commit
 * its counter increment — an exception would roll the transaction back and make the throttle
 * unenforceable.
 */
@Service
public class MfaService {

    public enum VerifyOutcome {
        /** Code accepted; the caller may now upgrade the session. */
        SUCCESS,
        /** Code rejected (bad TOTP, replayed TOTP, or unknown/spent recovery code). */
        INVALID,
        /** Too many recent failures; the challenge is locked and the caller must log in again. */
        THROTTLED,
        /** No confirmed MFA secret exists, so there is nothing to verify against. */
        NOT_ENROLLED
    }

    public enum ConfirmOutcome {
        SUCCESS,
        INVALID,
        /** No unconfirmed secret is pending — setup was never started, or is already finished. */
        NOT_PENDING
    }

    public record ConfirmResult(ConfirmOutcome outcome, List<String> recoveryCodes) {

        static ConfirmResult failure(ConfirmOutcome outcome) {
            return new ConfirmResult(outcome, List.of());
        }
    }

    private final MfaSecretRepository mfaSecrets;
    private final UserService userService;
    private final TotpService totpService;
    private final RecoveryCodeService recoveryCodeService;
    private final SecretCipher cipher;
    private final MfaProperties properties;
    private final Clock clock;

    public MfaService(MfaSecretRepository mfaSecrets, UserService userService, TotpService totpService,
                      RecoveryCodeService recoveryCodeService, SecretCipher cipher,
                      MfaProperties properties, Clock clock) {
        this.mfaSecrets = mfaSecrets;
        this.userService = userService;
        this.totpService = totpService;
        this.recoveryCodeService = recoveryCodeService;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(String username) {
        return mfaSecrets.findByUserUsername(username).map(MfaSecret::isConfirmed).orElse(false);
    }

    /**
     * Issues (or re-issues) an unconfirmed secret and returns the material the user needs to add the
     * account to their authenticator app. Calling this again before confirming rotates the secret,
     * so an abandoned setup does not leave a stale secret half-installed.
     */
    @Transactional
    public MfaSetupResponse beginSetup(String username) {
        User user = userService.require(username);
        Instant now = clock.instant();
        String secret = totpService.generateSecret();

        MfaSecret record = mfaSecrets.findByUserId(user.getId()).orElse(null);
        if (record == null) {
            record = new MfaSecret(user, cipher.encrypt(secret), now);
        } else if (record.isConfirmed()) {
            // Already enrolled: never hand out a new secret from the pre-auth endpoint.
            throw new IllegalStateException("MFA is already enrolled for this user");
        } else {
            record.replaceUnconfirmedSecret(cipher.encrypt(secret), now);
        }
        mfaSecrets.save(record);

        String uri = totpService.otpAuthUri(username, secret);
        return new MfaSetupResponse(secret, uri, totpService.qrDataUri(uri));
    }

    /** Verifies the first code against the pending secret, then confirms it and issues recovery codes. */
    @Transactional
    public ConfirmResult confirm(String username, String code) {
        Optional<MfaSecret> found = mfaSecrets.findByUserUsername(username);
        if (found.isEmpty() || found.get().isConfirmed()) {
            return ConfirmResult.failure(ConfirmOutcome.NOT_PENDING);
        }
        MfaSecret record = found.get();
        String secret = cipher.decrypt(record.getSecretCiphertext());

        OptionalLong step = totpService.findMatchingTimeStep(secret, code, record.getLastAcceptedTimeStep());
        if (step.isEmpty()) {
            return ConfirmResult.failure(ConfirmOutcome.INVALID);
        }

        Instant now = clock.instant();
        record.setLastAcceptedTimeStep(step.getAsLong());
        record.confirm(now);
        resetThrottle(record);
        List<String> codes = recoveryCodeService.issue(record);
        mfaSecrets.save(record);
        return new ConfirmResult(ConfirmOutcome.SUCCESS, codes);
    }

    /**
     * Verifies a challenge response, accepting either a 6-digit TOTP inside the drift window or an
     * unused recovery code.
     */
    @Transactional
    public VerifyOutcome verify(String username, String code) {
        Optional<MfaSecret> found = mfaSecrets.findByUserUsername(username);
        if (found.isEmpty() || !found.get().isConfirmed()) {
            return VerifyOutcome.NOT_ENROLLED;
        }
        MfaSecret record = found.get();
        Instant now = clock.instant();

        if (record.getLockedUntil() != null && now.isBefore(record.getLockedUntil())) {
            return VerifyOutcome.THROTTLED;
        }
        // The lock has aged out; start a clean window.
        if (record.getLockedUntil() != null) {
            resetThrottle(record);
        }

        String secret = cipher.decrypt(record.getSecretCiphertext());
        OptionalLong step = totpService.findMatchingTimeStep(secret, code, record.getLastAcceptedTimeStep());

        boolean accepted;
        if (step.isPresent()) {
            record.setLastAcceptedTimeStep(step.getAsLong());
            accepted = true;
        } else {
            accepted = recoveryCodeService.redeem(record, code);
        }

        if (accepted) {
            resetThrottle(record);
            mfaSecrets.save(record);
            return VerifyOutcome.SUCCESS;
        }

        registerFailure(record, now);
        mfaSecrets.save(record);
        return VerifyOutcome.INVALID;
    }

    /** Number of recovery codes the user has left, for display after a successful login. */
    @Transactional(readOnly = true)
    public long remainingRecoveryCodes(String username) {
        return mfaSecrets.findByUserUsername(username)
                .map(MfaSecret::unusedRecoveryCodeCount)
                .orElse(0L);
    }

    private void registerFailure(MfaSecret record, Instant now) {
        Instant windowStart = record.getAttemptWindowStartedAt();
        if (windowStart == null || !now.isBefore(windowStart.plus(properties.getAttemptWindow()))) {
            record.setAttemptWindowStartedAt(now);
            record.setFailedAttempts(0);
        }
        record.setFailedAttempts(record.getFailedAttempts() + 1);
        if (record.getFailedAttempts() >= properties.getMaxAttempts()) {
            // The attempt that hits the cap is still answered as invalid; the next one is throttled.
            record.setLockedUntil(now.plus(properties.getAttemptWindow()));
        }
    }

    private void resetThrottle(MfaSecret record) {
        record.setFailedAttempts(0);
        record.setAttemptWindowStartedAt(null);
        record.setLockedUntil(null);
    }
}
