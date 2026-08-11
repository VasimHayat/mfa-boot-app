package com.example.mfaapp.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Everything under {@code app.mfa}. */
@Validated
@ConfigurationProperties(prefix = "app.mfa")
public class MfaProperties {

    /** Label shown in the authenticator app, and the {@code issuer} in the otpauth URI. */
    @NotBlank
    private String issuer = "MFA Learning";

    /** Tolerated clock drift in 30-second time steps, either side of now. */
    @Min(0)
    private int window = 1;

    /** Base64-encoded AES key (16, 24 or 32 bytes) used to encrypt TOTP secrets at rest. */
    @NotBlank
    private String secretEncryptionKey;

    /** Failed verify attempts allowed inside {@link #attemptWindow} before the challenge locks. */
    @Min(1)
    private int maxAttempts = 5;

    /** Rolling window for {@link #maxAttempts}, and the lock duration once it is exceeded. */
    private Duration attemptWindow = Duration.ofMinutes(15);

    /** Number of single-use recovery codes handed out at confirm time. */
    @Min(1)
    private int recoveryCodeCount = 10;

    /**
     * BCrypt cost for recovery codes. Lower than the password cost on purpose: recovery codes are
     * high-entropy random strings, not user-chosen secrets, and a verify attempt may have to try
     * every unused code.
     */
    @Min(4)
    private int recoveryCodeBcryptStrength = 10;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public int getWindow() {
        return window;
    }

    public void setWindow(int window) {
        this.window = window;
    }

    public String getSecretEncryptionKey() {
        return secretEncryptionKey;
    }

    public void setSecretEncryptionKey(String secretEncryptionKey) {
        this.secretEncryptionKey = secretEncryptionKey;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getAttemptWindow() {
        return attemptWindow;
    }

    public void setAttemptWindow(Duration attemptWindow) {
        this.attemptWindow = attemptWindow;
    }

    public int getRecoveryCodeCount() {
        return recoveryCodeCount;
    }

    public void setRecoveryCodeCount(int recoveryCodeCount) {
        this.recoveryCodeCount = recoveryCodeCount;
    }

    public int getRecoveryCodeBcryptStrength() {
        return recoveryCodeBcryptStrength;
    }

    public void setRecoveryCodeBcryptStrength(int recoveryCodeBcryptStrength) {
        this.recoveryCodeBcryptStrength = recoveryCodeBcryptStrength;
    }
}
