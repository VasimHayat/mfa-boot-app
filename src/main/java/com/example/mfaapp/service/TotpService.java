package com.example.mfaapp.service;

import com.example.mfaapp.config.MfaProperties;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * RFC 6238 TOTP: SHA1, 6 digits, 30-second steps.
 *
 * <p>Time comes from an injected {@link Clock} rather than {@code System.currentTimeMillis()}, which
 * is what lets tests pin the current time step and exercise the drift window and replay guard
 * deterministically.
 */
@Service
public class TotpService {

    public static final int TIME_STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    private static final int QR_PIXELS = 264;

    private final Clock clock;
    private final MfaProperties properties;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, DIGITS);

    public TotpService(Clock clock, MfaProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** A fresh 160-bit shared secret, Base32 encoded as authenticator apps expect. */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    public long currentTimeStep() {
        return Math.floorDiv(clock.instant().getEpochSecond(), TIME_STEP_SECONDS);
    }

    /**
     * The {@code otpauth://} URI Google Authenticator scans. The label repeats the issuer as a
     * prefix ({@code Issuer:user}) so the entry is unambiguous in apps that show only the label.
     */
    public String otpAuthUri(String username, String secretBase32) {
        String issuer = properties.getIssuer();
        String label = encode(issuer) + ":" + encode(username);
        return "otpauth://totp/" + label
                + "?secret=" + encode(secretBase32)
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    /** PNG QR code as a {@code data:} URI. Nothing is written to disk. */
    public String qrDataUri(String otpAuthUri) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix matrix = new QRCodeWriter()
                    .encode(otpAuthUri, BarcodeFormat.QR_CODE, QR_PIXELS, QR_PIXELS, hints);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Unable to render the MFA QR code", e);
        }
    }

    /**
     * Checks {@code code} against every time step inside the configured drift window, skipping any
     * step at or below {@code lastAcceptedTimeStep} so an intercepted code cannot be replayed while
     * it is still nominally valid.
     *
     * @return the time step that matched, or empty if none did
     */
    public OptionalLong findMatchingTimeStep(String secretBase32, String code, Long lastAcceptedTimeStep) {
        if (!isWellFormedCode(code)) {
            return OptionalLong.empty();
        }
        long current = currentTimeStep();
        int window = properties.getWindow();
        for (long step = current - window; step <= current + window; step++) {
            if (step < 0) {
                continue;
            }
            if (lastAcceptedTimeStep != null && step <= lastAcceptedTimeStep) {
                continue;
            }
            if (matches(secretBase32, code, step)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** Exposed so tests (and the seeder) can produce a code for a known step. */
    public String generateCode(String secretBase32, long timeStep) {
        try {
            return codeGenerator.generate(secretBase32, timeStep);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException("Unable to generate a TOTP code", e);
        }
    }

    public static boolean isWellFormedCode(String code) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private boolean matches(String secretBase32, String code, long step) {
        byte[] expected = generateCode(secretBase32, step).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, code.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(String value) {
        // URLEncoder is form encoding; otpauth wants %20 rather than + for spaces.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
