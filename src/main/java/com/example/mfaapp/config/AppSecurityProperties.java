package com.example.mfaapp.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything under {@code app.security}. */
@Validated
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    /** BCrypt cost for user passwords. Lowered only in the test profile to keep tests quick. */
    @Min(4)
    private int bcryptStrength = 12;

    /** HSTS is only meaningful over HTTPS, so it stays off outside the prod profile. */
    private boolean hstsEnabled = false;

    public int getBcryptStrength() {
        return bcryptStrength;
    }

    public void setBcryptStrength(int bcryptStrength) {
        this.bcryptStrength = bcryptStrength;
    }

    public boolean isHstsEnabled() {
        return hstsEnabled;
    }

    public void setHstsEnabled(boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }
}
