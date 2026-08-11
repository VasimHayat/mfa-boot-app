package com.example.mfaapp.config;

import com.example.mfaapp.domain.Role;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The half-authenticated state between a correct password and a cleared MFA challenge.
 *
 * <p>It carries {@code ROLE_PRE_AUTH} and nothing else, so it can reach the MFA endpoints and no
 * others. A distinct type also means "has the user finished logging in?" is a type check rather
 * than an authority-string convention.
 */
public class PreAuthenticationToken extends AbstractAuthenticationToken {

    private final String username;

    public PreAuthenticationToken(String username) {
        super(List.of(new SimpleGrantedAuthority(Role.PRE_AUTH.authority())));
        this.username = username;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    @Override
    public String getName() {
        return username;
    }

    /** True when {@code authentication} has passed the password step but not the MFA step. */
    public static boolean isPreAuth(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        if (authentication instanceof PreAuthenticationToken) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> Role.PRE_AUTH.authority().equals(authority.getAuthority()));
    }
}
