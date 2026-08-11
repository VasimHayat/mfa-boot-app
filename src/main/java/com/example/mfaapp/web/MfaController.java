package com.example.mfaapp.web;

import com.example.mfaapp.domain.User;
import com.example.mfaapp.service.AuthSessionService;
import com.example.mfaapp.service.MfaService;
import com.example.mfaapp.service.MfaService.ConfirmResult;
import com.example.mfaapp.service.UserService;
import com.example.mfaapp.web.dto.AuthDtos.ErrorResponse;
import com.example.mfaapp.web.dto.AuthDtos.MfaCodeRequest;
import com.example.mfaapp.web.dto.AuthDtos.MfaConfirmResponse;
import com.example.mfaapp.web.dto.AuthDtos.MfaSetupResponse;
import com.example.mfaapp.web.dto.AuthDtos.StatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The MFA half of login. Every endpoint here requires {@code ROLE_PRE_AUTH}. */
@RestController
@RequestMapping("/api/mfa")
public class MfaController {

    private static final String GENERIC_CODE_FAILURE = "Invalid verification code";

    private final MfaService mfaService;
    private final UserService userService;
    private final AuthSessionService authSessionService;

    public MfaController(MfaService mfaService, UserService userService,
                         AuthSessionService authSessionService) {
        this.mfaService = mfaService;
        this.userService = userService;
        this.authSessionService = authSessionService;
    }

    /**
     * Issues an unconfirmed secret plus the QR code and {@code otpauth://} URI needed to add the
     * account to an authenticator app. The response is never cached.
     */
    @GetMapping("/setup")
    public ResponseEntity<MfaSetupResponse> setup(Authentication authentication) {
        MfaSetupResponse body = mfaService.beginSetup(authentication.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Confirms enrolment and returns the recovery codes. This is the only time the plaintext codes
     * exist outside the user's own records — only BCrypt hashes are persisted.
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@Valid @RequestBody MfaCodeRequest request,
                                     Authentication authentication) {
        ConfirmResult result = mfaService.confirm(authentication.getName(), request.code());
        return switch (result.outcome()) {
            case SUCCESS -> ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new MfaConfirmResponse("MFA_ENROLLED", result.recoveryCodes()));
            case INVALID -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("invalid_code", GENERIC_CODE_FAILURE));
            case NOT_PENDING -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("no_pending_enrollment",
                            "There is no MFA enrolment awaiting confirmation"));
        };
    }

    /**
     * Answers the challenge with a TOTP or a recovery code. Success replaces the pre-auth session
     * with a brand-new one holding the user's real authorities.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@Valid @RequestBody MfaCodeRequest request,
                                    Authentication authentication,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        String username = authentication.getName();
        MfaService.VerifyOutcome outcome = mfaService.verify(username, request.code());

        return switch (outcome) {
            case SUCCESS -> {
                User user = userService.require(username);
                authSessionService.promoteToAuthenticated(httpRequest, httpResponse, user);
                yield ResponseEntity.ok(new StatusResponse("AUTHENTICATED"));
            }
            case INVALID -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("invalid_code", GENERIC_CODE_FAILURE));
            case THROTTLED -> {
                // Locked out: drop the pre-auth session so the only way forward is a fresh login.
                authSessionService.discardSession(httpRequest, httpResponse);
                yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(new ErrorResponse("mfa_locked",
                                "Too many failed attempts. Please sign in again."));
            }
            case NOT_ENROLLED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("mfa_not_enrolled", "MFA enrolment is not complete"));
        };
    }
}
