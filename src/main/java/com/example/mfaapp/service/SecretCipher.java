package com.example.mfaapp.service;

import com.example.mfaapp.config.MfaProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * AES-GCM envelope for TOTP secrets. Stored form is {@code v1:base64(iv || ciphertext || tag)} with
 * a fresh 96-bit IV per encryption, so the same secret never produces the same ciphertext twice.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final Set<Integer> VALID_KEY_LENGTHS = Set.of(16, 24, 32);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(MfaProperties properties) {
        this.key = loadKey(properties.getSecretEncryptionKey());
    }

    private static SecretKey loadKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "app.mfa.secret-encryption-key is required; supply a Base64-encoded AES key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.mfa.secret-encryption-key must be valid Base64", e);
        }
        if (!VALID_KEY_LENGTHS.contains(raw.length)) {
            throw new IllegalStateException(
                    "app.mfa.secret-encryption-key must decode to 16, 24 or 32 bytes but was " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt MFA secret", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Stored MFA secret is not in the expected v1 envelope");
        }
        byte[] envelope = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
        if (envelope.length <= IV_LENGTH) {
            throw new IllegalStateException("Stored MFA secret envelope is truncated");
        }
        try {
            byte[] iv = Arrays.copyOfRange(envelope, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(envelope, IV_LENGTH, envelope.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Includes AEADBadTagException, i.e. wrong key or tampered ciphertext.
            throw new IllegalStateException("Unable to decrypt MFA secret", e);
        }
    }
}
