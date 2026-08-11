package com.example.mfaapp.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request/response payloads for the authentication and MFA endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 200) String password) {
    }

    /**
     * Single-field status envelope used by login, verify and logout.
     * Values: {@code MFA_REQUIRED}, {@code MFA_SETUP_REQUIRED}, {@code AUTHENTICATED},
     * {@code LOGGED_OUT}.
     */
    public record StatusResponse(String status) {
    }

    public record MfaSetupResponse(String secretBase32, String otpAuthUri, String qrDataUri) {
    }

    /** Accepts either a 6-digit TOTP or a recovery code, so the length bound is generous. */
    public record MfaCodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    /** Recovery codes are returned exactly once, at confirm time. */
    public record MfaConfirmResponse(String status, List<String> recoveryCodes) {
    }

    public record MeResponse(String username, List<String> roles, boolean mfaEnabled) {
    }

    public record ErrorResponse(String error, String message) {
    }
}
