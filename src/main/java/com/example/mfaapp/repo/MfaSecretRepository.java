package com.example.mfaapp.repo;

import com.example.mfaapp.domain.MfaSecret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MfaSecretRepository extends JpaRepository<MfaSecret, Long> {

    Optional<MfaSecret> findByUserId(Long userId);

    Optional<MfaSecret> findByUserUsername(String username);

    boolean existsByUserIdAndConfirmedTrue(Long userId);
}
