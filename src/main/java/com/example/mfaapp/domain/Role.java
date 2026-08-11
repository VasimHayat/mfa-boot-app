package com.example.mfaapp.domain;

/**
 * Application roles. {@link #PRE_AUTH} is special: it is never persisted on a user, it is only
 * granted to the short-lived session that has passed password check but not yet cleared MFA.
 */
public enum Role {

    PRE_AUTH,
    USER,
    ADMIN,
    ENGINEER,
    COMPLIANCE_OFFICER;

    /** Spring Security authority string, e.g. {@code ROLE_COMPLIANCE_OFFICER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
