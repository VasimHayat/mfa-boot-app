package com.example.mfaapp.service;

import com.example.mfaapp.config.MfaProperties;
import com.example.mfaapp.domain.MfaSecret;
import com.example.mfaapp.domain.RecoveryCode;
import dev.samstevens.totp.recovery.RecoveryCodeGenerator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Issues and redeems single-use MFA recovery codes. Plaintext exists only inside
 * {@link #issue(MfaSecret)}'s return value — everything persisted is a BCrypt hash.
 */
@Service
public class RecoveryCodeService {

    private final MfaProperties properties;
    private final Clock clock;
    private final PasswordEncoder encoder;
    private final RecoveryCodeGenerator generator = new RecoveryCodeGenerator();

    public RecoveryCodeService(MfaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.encoder = new BCryptPasswordEncoder(properties.getRecoveryCodeBcryptStrength());
    }

    /**
     * Generates a fresh set of codes, replacing any existing ones, and returns the plaintext for the
     * one and only time it is available.
     */
    public List<String> issue(MfaSecret secret) {
        List<String> plaintext = List.of(generator.generateCodes(properties.getRecoveryCodeCount()));
        // Hash the normalized form so redeem() can normalize the candidate the same way.
        secret.replaceRecoveryCodes(plaintext.stream().map(code -> encoder.encode(normalize(code))).toList());
        return plaintext;
    }

    /**
     * Redeems {@code candidate} if it matches an unused code, flipping that code to used.
     *
     * @return true when the code was valid and has now been consumed
     */
    public boolean redeem(MfaSecret secret, String candidate) {
        String normalized = normalize(candidate);
        if (normalized.isEmpty()) {
            return false;
        }
        Optional<RecoveryCode> match = secret.mutableRecoveryCodes().stream()
                .filter(code -> !code.isUsed())
                .filter(code -> encoder.matches(normalized, code.getCodeHash()))
                .findFirst();
        match.ifPresent(code -> code.markUsed(clock.instant()));
        return match.isPresent();
    }

    /** Codes are shown grouped and may be pasted back with stray spacing or a different case. */
    private static String normalize(String candidate) {
        if (candidate == null) {
            return "";
        }
        return candidate.trim().replace(" ", "").toLowerCase(Locale.ROOT);
    }
}
