package com.example.mfaapp;

import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A session that has passed the password step but not the MFA step is authenticated as far as the
 * servlet is concerned. These tests pin the rule that it still cannot read any learning data.
 */
class PreAuthAccessTest extends IntegrationTestBase {

    private static final String USERNAME = "bob";

    @Test
    @DisplayName("Every learning endpoint returns 401 for a session that has not cleared MFA")
    void learningEndpointsRejectAPreAuthSession() throws Exception {
        newUser(USERNAME, Role.USER);
        enrollMfa(USERNAME);
        newModule("sec-basics", "Security Basics", ModuleCategory.SECURITY, Difficulty.BEGINNER, 3);

        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_REQUIRED"))
                .andReturn();
        MockHttpSession preAuth = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/modules").session(preAuth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("mfa_required"));
        mockMvc.perform(get("/api/modules/sec-basics").session(preAuth))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/modules/sec-basics/enroll").session(preAuth).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/learning/summary").session(preAuth))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").session(preAuth))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Learning endpoints also reject an anonymous caller")
    void learningEndpointsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @DisplayName("Mutating endpoints refuse a request without a CSRF token")
    void mutatingEndpointsRequireCsrf() throws Exception {
        newUser(USERNAME, Role.USER);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", USERNAME, "password", PASSWORD))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The SPA shell and its vendored assets are public, and deep links serve the shell")
    void staticShellIsPublic() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/vendor/vue.global.prod.js")).andExpect(status().isOk());
        mockMvc.perform(get("/components/LoginView.js")).andExpect(status().isOk());
        // Deep link: forwarded to the shell rather than 401'd, so Vue can route it client-side.
        mockMvc.perform(get("/modules/sec-basics"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        // An unknown path under /api never falls through to the SPA shell. Anonymously it is
        // refused by the filter chain before routing, which also avoids advertising which API
        // paths exist; authenticated, it is a plain 404.
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "USER")
    @DisplayName("An unknown API path 404s for an authenticated caller rather than serving the shell")
    void unknownApiPathIsNotTheShell() throws Exception {
        newUser(USERNAME, Role.USER);

        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("<div id=\"app\">"));
    }

    @Test
    @DisplayName("Responses carry the expected security headers")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Frame-Options", "DENY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Security-Policy",
                                org.hamcrest.Matchers.allOf(
                                        // 'unsafe-eval' is required by Vue's in-browser template
                                        // compiler; no third-party or inline script is allowed.
                                        org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-eval'"),
                                        org.hamcrest.Matchers.containsString("img-src 'self' data:"),
                                        org.hamcrest.Matchers.containsString("object-src 'none'"),
                                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'"))));
    }
}
