package com.example.mfaapp.service;

import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.MfaSecretRepository;
import com.example.mfaapp.repo.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository users;
    private final MfaSecretRepository mfaSecrets;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository users, MfaSecretRepository mfaSecrets,
                       PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.mfaSecrets = mfaSecrets;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Used by {@code DaoAuthenticationProvider} for the password step only. The authorities returned
     * here are the user's real roles; they are not written to the session until MFA has been
     * cleared — see {@code AuthSessionService}.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(authorities(user.getRoles()))
                .build();
    }

    public static List<SimpleGrantedAuthority> authorities(Set<Role> roles) {
        return roles.stream().map(role -> new SimpleGrantedAuthority(role.authority())).toList();
    }

    @Transactional(readOnly = true)
    public User require(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }

    /** True once the user has an MFA secret they have proved possession of. */
    @Transactional(readOnly = true)
    public boolean isMfaEnabled(Long userId) {
        return mfaSecrets.existsByUserIdAndConfirmedTrue(userId);
    }

    @Transactional
    public User createUser(String username, String rawPassword, Set<Role> roles) {
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        return users.save(new User(username, passwordEncoder.encode(rawPassword), roles, clock.instant()));
    }
}
