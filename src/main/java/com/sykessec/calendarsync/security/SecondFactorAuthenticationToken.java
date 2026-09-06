package com.sykessec.calendarsync.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * The unauthenticated request to verify a second factor: which pending user,
 * and what they typed. Never becomes the authenticated token itself -
 * SecondFactorAuthenticationProvider answers with a fully populated
 * UsernamePasswordAuthenticationToken so that the rest of the application sees
 * exactly the same principal shape it would from an ordinary login.
 */
public class SecondFactorAuthenticationToken extends AbstractAuthenticationToken {

    private final Long userId;
    private final String submittedCode;
    private final String submittedRecoveryCode;

    public SecondFactorAuthenticationToken(Long userId, String submittedCode, String submittedRecoveryCode) {
        // Empty rather than null: Spring Security 7 added a builder-taking
        // constructor, so a bare null is ambiguous between the two overloads.
        super(List.of());
        this.userId = userId;
        this.submittedCode = submittedCode;
        this.submittedRecoveryCode = submittedRecoveryCode;
        setAuthenticated(false);
    }

    public Long getUserId() {
        return userId;
    }

    public String getSubmittedCode() {
        return submittedCode;
    }

    public String getSubmittedRecoveryCode() {
        return submittedRecoveryCode;
    }

    @Override
    public Object getCredentials() {
        return submittedCode != null ? submittedCode : submittedRecoveryCode;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }
}
