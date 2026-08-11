package com.example.mfaapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;

/** A single-use MFA recovery code. Only the BCrypt hash is ever persisted. */
@Embeddable
public class RecoveryCode {

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "used", nullable = false)
    private boolean used;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RecoveryCode() {
        // for JPA
    }

    public RecoveryCode(String codeHash) {
        this.codeHash = codeHash;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public boolean isUsed() {
        return used;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void markUsed(Instant at) {
        this.used = true;
        this.usedAt = at;
    }
}
