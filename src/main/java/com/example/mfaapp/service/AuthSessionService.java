package com.example.mfaapp.service;

import com.example.mfaapp.config.PreAuthenticationToken;
import com.example.mfaapp.domain.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Owns the servlet session across the two-step login.
 *
 * <p>Both transitions — anonymous to pre-auth, and pre-auth to authenticated — discard the old
 * session and start a new one, so a session id an attacker managed to fix before login is never the
 * session id that ends up holding real authorities.
 */
@Service
public class AuthSessionService {

    private final SecurityContextRepository securityContextRepository;

    public AuthSessionService(SecurityContextRepository securityContextRepository) {
        this.securityContextRepository = securityContextRepository;
    }

    /** Password accepted, MFA outstanding. The session holds {@code ROLE_PRE_AUTH} only. */
    public void startPreAuthSession(HttpServletRequest request, HttpServletResponse response, String username) {
        store(request, response, new PreAuthenticationToken(username));
    }

    /** MFA cleared. A brand-new session receives the user's real authorities. */
    public void promoteToAuthenticated(HttpServletRequest request, HttpServletResponse response, User user) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user.getUsername(), null, UserService.authorities(user.getRoles()));
        store(request, response, authentication);
    }

    /**
     * Drops the session and its cookie. The CSRF cookie is deliberately left in place: it is not a
     * credential, and expiring it would leave the SPA unable to POST a subsequent login.
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        invalidateCurrentSession(request);
        SecurityContextHolder.clearContext();
        expireCookie(request, response, "JSESSIONID");
    }

    /** Used when a challenge is locked out: the pre-auth session must not survive. */
    public void discardSession(HttpServletRequest request, HttpServletResponse response) {
        logout(request, response);
    }

    private void store(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        invalidateCurrentSession(request);
        // Create the new session up front so the repository writes into it rather than deciding
        // whether it is allowed to create one.
        request.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private void invalidateCurrentSession(HttpServletRequest request) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
    }

    private void expireCookie(HttpServletRequest request, HttpServletResponse response, String name) {
        Cookie expired = new Cookie(name, null);
        expired.setPath(contextPathOrRoot(request));
        expired.setMaxAge(0);
        expired.setHttpOnly(false);
        response.addCookie(expired);
    }

    private static String contextPathOrRoot(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty() ? "/" : contextPath;
    }
}
