package com.example.mfaapp.web;

import com.example.mfaapp.service.AuthSessionService;
import com.example.mfaapp.service.MfaService;
import com.example.mfaapp.web.dto.AuthDtos.ErrorResponse;
import com.example.mfaapp.web.dto.AuthDtos.LoginRequest;
import com.example.mfaapp.web.dto.AuthDtos.StatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Deliberately identical for an unknown user and a wrong password. */
    private static final String GENERIC_LOGIN_FAILURE = "Invalid username or password";

    private final AuthenticationManager authenticationManager;
    private final AuthSessionService authSessionService;
    private final MfaService mfaService;

    public AuthController(AuthenticationManager authenticationManager,
                          AuthSessionService authSessionService,
                          MfaService mfaService) {
        this.authenticationManager = authenticationManager;
        this.authSessionService = authSessionService;
        this.mfaService = mfaService;
    }

    /**
     * Step one of two. A correct password produces a session that carries {@code ROLE_PRE_AUTH} and
     * nothing else; the response says which MFA step comes next.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            // No session mutation on failure, and no hint about which half of the pair was wrong.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("invalid_credentials", GENERIC_LOGIN_FAILURE));
        }

        authSessionService.startPreAuthSession(httpRequest, httpResponse, request.username());
        String status = mfaService.isEnrolled(request.username()) ? "MFA_REQUIRED" : "MFA_SETUP_REQUIRED";
        return ResponseEntity.ok(new StatusResponse(status));
    }

    @PostMapping("/logout")
    public ResponseEntity<StatusResponse> logout(HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        authSessionService.logout(httpRequest, httpResponse);
        return ResponseEntity.ok(new StatusResponse("LOGGED_OUT"));
    }

    /**
     * Exists so the SPA can guarantee it holds an XSRF-TOKEN cookie before its first POST. The CSRF
     * filter writes the cookie as a side effect of handling this request.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }
}
