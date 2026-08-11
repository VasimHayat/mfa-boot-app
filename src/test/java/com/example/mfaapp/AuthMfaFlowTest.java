package com.example.mfaapp;

import com.example.mfaapp.domain.Role;
import com.example.mfaapp.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthMfaFlowTest extends IntegrationTestBase {

    private static final String USERNAME = "alice";

    @Test
    @DisplayName("A wrong password returns 401 with a message that does not reveal whether the user exists")
    void badPasswordIsRejected() throws Exception {
        newUser(USERNAME, Role.USER);

        String wrongPassword = loginRaw(USERNAME, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andReturn().getResponse().getContentAsString();

        String unknownUser = loginRaw("nobody-at-all", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("A user with no MFA secret is told to enrol, and setup returns a QR code plus otpauth URI")
    void loginWithoutEnrolmentAsksForSetup() throws Exception {
        newUser(USERNAME, Role.USER);

        MvcResult login = loginRaw(USERNAME, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_SETUP_REQUIRED"))
                .andReturn();

        mockMvc.perform(get("/api/mfa/setup").session(sessionOf(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretBase32").isNotEmpty())
                .andExpect(jsonPath("$.qrDataUri").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.otpAuthUri").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("otpauth://totp/MFA%20Learning%20Test:alice?secret="),
                        org.hamcrest.Matchers.containsString("&issuer=MFA%20Learning%20Test"),
                        org.hamcrest.Matchers.containsString("&algorithm=SHA1"),
                        org.hamcrest.Matchers.containsString("&digits=6"),
                        org.hamcrest.Matchers.containsString("&period=30"))));
    }

    @Test
    @DisplayName("/api/me is refused while the session has only cleared the password step")
    void meIsRefusedBeforeMfaVerify() throws Exception {
        newUser(USERNAME, Role.USER);
        enrollMfa(USERNAME);

        MvcResult login = loginRaw(USERNAME, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_REQUIRED"))
                .andReturn();

        mockMvc.perform(get("/api/me").session(sessionOf(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("mfa_required"));
    }

    @Test
    @DisplayName("A wrong TOTP is refused; the right one authenticates and rotates the session id")
    void wrongTotpRejectedCorrectTotpAccepted() throws Exception {
        newUser(USERNAME, Role.USER);
        String secret = enrollMfa(USERNAME);
        // Enrolment consumed the current time step, so move to the next one for a usable code.
        testClock().advance(Duration.ofSeconds(30));

        MockHttpSession preAuth = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        String preAuthSessionId = preAuth.getId();

        verify(preAuth, "000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid verification code"));

        MvcResult verified = verify(preAuth, currentTotp(secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andReturn();

        MockHttpSession authenticated = sessionOf(verified);
        assertThat(authenticated.getId())
                .as("a new session must be created on MFA success (session fixation protection)")
                .isNotEqualTo(preAuthSessionId);

        mockMvc.perform(get("/api/me").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.mfaEnabled").value(true))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }

    @Test
    @DisplayName("A recovery code authenticates once and is refused the second time")
    void recoveryCodeIsSingleUse() throws Exception {
        newUser(USERNAME, Role.USER);
        List<String> codes = enrollMfaWithCodes(USERNAME).recoveryCodes();
        assertThat(codes).hasSize(10);
        String code = codes.get(3);

        MockHttpSession first = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        verify(first, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));

        MockHttpSession second = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        verify(second, code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));

        assertThat(mfaService.remainingRecoveryCodes(USERNAME)).isEqualTo(9);
    }

    @Test
    @DisplayName("A TOTP already accepted cannot be replayed, but the next step's code still works")
    void totpCannotBeReplayed() throws Exception {
        newUser(USERNAME, Role.USER);
        String secret = enrollMfa(USERNAME);

        // enrollMfa() confirmed with the code for the current step, so that step is now spent.
        String spentCode = currentTotp(secret);
        MockHttpSession first = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        verify(first, spentCode)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_code"));

        testClock().advance(Duration.ofSeconds(30));
        MockHttpSession second = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        String freshCode = currentTotp(secret);
        assertThat(freshCode).isNotEqualTo(spentCode);
        verify(second, freshCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));

        // And the freshly accepted code is itself now spent.
        MockHttpSession third = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        verify(third, freshCode).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The sixth failed verify attempt is throttled with 429 and drops the pre-auth session")
    void rateLimitingReturns429AfterFiveFailures() throws Exception {
        newUser(USERNAME, Role.USER);
        enrollMfa(USERNAME);

        MockHttpSession preAuth = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());

        for (int attempt = 1; attempt <= 5; attempt++) {
            verify(preAuth, "000000")
                    .andExpect(status().isUnauthorized());
        }

        verify(preAuth, "000000")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("mfa_locked"));

        assertThat(preAuth.isInvalid())
                .as("being locked out must force a fresh login")
                .isTrue();
        mockMvc.perform(get("/api/mfa/setup").session(preAuth)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Once the lock window has passed, a valid code is accepted again")
    void lockExpiresAfterTheAttemptWindow() throws Exception {
        newUser(USERNAME, Role.USER);
        String secret = enrollMfa(USERNAME);

        MockHttpSession preAuth = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        for (int attempt = 1; attempt <= 5; attempt++) {
            verify(preAuth, "000000").andExpect(status().isUnauthorized());
        }
        verify(preAuth, "000000").andExpect(status().isTooManyRequests());

        testClock().advance(Duration.ofMinutes(16));
        MockHttpSession retry = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        verify(retry, currentTotp(secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    @Test
    @DisplayName("The MFA endpoints are unreachable without a pre-auth session")
    void mfaEndpointsRequirePreAuth() throws Exception {
        newUser(USERNAME, Role.USER);

        mockMvc.perform(get("/api/mfa/setup")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mfa/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "123456"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Confirming enrolment returns ten recovery codes exactly once, and stores only hashes")
    void confirmReturnsRecoveryCodesOnce() throws Exception {
        newUser(USERNAME, Role.USER);
        MockHttpSession preAuth = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());

        String secret = objectMapper.readTree(
                        mockMvc.perform(get("/api/mfa/setup").session(preAuth))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .get("secretBase32").asText();

        String body = mockMvc.perform(post("/api/mfa/confirm").session(preAuth).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", currentTotp(secret)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_ENROLLED"))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn().getResponse().getContentAsString();

        List<String> plaintext = objectMapper.readValue(
                objectMapper.readTree(body).get("recoveryCodes").toString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

        // Nothing recoverable is persisted: no stored hash equals a plaintext code, and the secret
        // column holds an AES-GCM envelope rather than the Base32 secret.
        var stored = mfaSecrets.findByUserUsername(USERNAME).orElseThrow();
        assertThat(stored.getRecoveryCodes()).hasSize(10);
        assertThat(stored.getRecoveryCodes()).allSatisfy(recoveryCode ->
                assertThat(plaintext).doesNotContain(recoveryCode.getCodeHash()));
        assertThat(stored.getSecretCiphertext()).startsWith("v1:").doesNotContain(secret);

        // A second confirm has nothing pending to confirm.
        mockMvc.perform(post("/api/mfa/confirm").session(preAuth).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", currentTotp(secret)))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Logout invalidates the session and expires its cookie")
    void logoutInvalidatesTheSession() throws Exception {
        newUser(USERNAME, Role.USER);
        String secret = enrollMfa(USERNAME);
        testClock().advance(Duration.ofSeconds(30));

        MockHttpSession preAuth = sessionOf(loginRaw(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn());
        MockHttpSession authenticated = sessionOf(verify(preAuth, currentTotp(secret))
                .andExpect(status().isOk()).andReturn());

        MvcResult logout = mockMvc.perform(post("/api/auth/logout").session(authenticated).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOGGED_OUT"))
                .andReturn();

        assertThat(authenticated.isInvalid()).isTrue();
        assertThat(logout.getResponse().getCookie("JSESSIONID")).isNotNull();
        assertThat(logout.getResponse().getCookie("JSESSIONID").getMaxAge()).isZero();

        mockMvc.perform(get("/api/me").session(authenticated)).andExpect(status().isUnauthorized());
    }

    // --- helpers -------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions loginRaw(String username, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "password", password))));
    }

    private org.springframework.test.web.servlet.ResultActions verify(MockHttpSession session, String code)
            throws Exception {
        return mockMvc.perform(post("/api/mfa/verify").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("code", code))));
    }

    /** The session the handler ended up with, which is a new one whenever authorities changed. */
    private static MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
