package com.example.mfaapp.config;

import com.example.mfaapp.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.io.IOException;
import java.util.Map;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Self-hosted scripts and styles only, plus {@code data:} images so the enrolment QR code can be
     * inlined.
     *
     * <p>Two relaxations, both forced by running Vue and PrimeVue from plain script tags with no
     * bundler:
     * <ul>
     *   <li>{@code style-src 'unsafe-inline'} — PrimeVue injects component styles into
     *       {@code <style>} elements at runtime.</li>
     *   <li>{@code script-src 'unsafe-eval'} — the components declare string templates, which Vue's
     *       in-browser compiler turns into render functions via {@code new Function}. Removing this
     *       means precompiling templates, which requires the Node build step this app deliberately
     *       does without. Note {@code 'self'} still blocks third-party and inline {@code <script>}.</li>
     * </ul>
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-eval'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private final AppSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(AppSecurityProperties securityProperties, ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(securityProperties.getBcryptStrength());
    }

    /** Shared with {@code AuthSessionService} so login, MFA promotion and logout agree on storage. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * The password half of the login. Wired by hand rather than through {@code formLogin()} because
     * the credentials arrive as JSON and a successful password check must produce a
     * {@link PreAuthenticationToken}, not a fully authenticated session.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Unknown user and wrong password must be indistinguishable to the caller.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        // Opting out of deferred token loading means XSRF-TOKEN is set on the very first GET, which is
        // what lets the SPA send X-XSRF-TOKEN on its first mutating request.
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .sameSite("Lax")
                .secure(securityProperties.isHstsEnabled()));

        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        // Public: the SPA shell, its vendored assets, and the login/logout endpoints.
                        .requestMatchers(HttpMethod.GET, "/index.html", "/app.js", "/favicon.ico").permitAll()
                        .requestMatchers("/vendor/**", "/components/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        // Deep links must serve the shell even when nobody is logged in; the SPA then
                        // calls /api/me and routes to the login view itself.
                        .requestMatchers(spaShell()).permitAll()
                        // The MFA challenge is reachable only from the half-authenticated state.
                        .requestMatchers(HttpMethod.GET, "/api/mfa/setup").hasRole(Role.PRE_AUTH.name())
                        .requestMatchers(HttpMethod.POST, "/api/mfa/confirm", "/api/mfa/verify")
                        .hasRole(Role.PRE_AUTH.name())
                        // Everything else needs a session that has actually cleared MFA.
                        .anyRequest().access(fullyAuthenticated()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "unauthorized",
                                        "Authentication required"))
                        .accessDeniedHandler(accessDeniedHandler()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(withDefaults())
                        .httpStrictTransportSecurity(hsts -> {
                            if (securityProperties.isHstsEnabled()) {
                                hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000);
                            } else {
                                hsts.disable();
                            }
                        })
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)));

        return http.build();
    }

    /** Any GET outside {@code /api/} — the static SPA and its client-side routes. */
    private static RequestMatcher spaShell() {
        return request -> HttpMethod.GET.matches(request.getMethod())
                && !requestPath(request).startsWith("/api/");
    }

    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    /**
     * Authenticated <em>and</em> past MFA. Plain {@code authenticated()} is not enough: a
     * {@link PreAuthenticationToken} is technically authenticated, and must not reach the learning
     * endpoints.
     */
    private static AuthorizationManager<RequestAuthorizationContext> fullyAuthenticated() {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            boolean ok = auth != null
                    && auth.isAuthenticated()
                    && !(auth instanceof AnonymousAuthenticationToken)
                    && !PreAuthenticationToken.isPreAuth(auth);
            return new AuthorizationDecision(ok);
        };
    }

    /**
     * A pre-auth session is treated as not-yet-authenticated (401) rather than forbidden (403): the
     * fix is to finish the MFA challenge, not to acquire a different role. 403 is reserved for a
     * fully logged-in user who simply lacks a required role.
     */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            if (ex instanceof CsrfException) {
                // A missing or stale token is a request problem, not an identity problem — 401 here
                // would send the SPA into a pointless re-login loop.
                writeError(response, HttpStatus.FORBIDDEN, "invalid_csrf_token",
                        "Missing or invalid CSRF token");
                return;
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean notLoggedIn = auth == null || !auth.isAuthenticated()
                    || auth instanceof AnonymousAuthenticationToken;
            if (notLoggedIn) {
                writeError(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required");
            } else if (PreAuthenticationToken.isPreAuth(auth)) {
                writeError(response, HttpStatus.UNAUTHORIZED, "mfa_required",
                        "Multi-factor authentication has not been completed");
            } else {
                writeError(response, HttpStatus.FORBIDDEN, "forbidden", "Access denied");
            }
        };
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", error, "message", message));
    }
}
