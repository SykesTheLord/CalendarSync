package com.sykessec.calendarsync.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Feeds LoginAttemptService and leaves a trail of who signed in from where.
 * Until this existed, a successful break-in left no record at all: the
 * application logged provider errors and rule decisions in detail but said
 * nothing whatsoever about authentication, so "was this account used by
 * someone else?" was unanswerable from the logs.
 *
 * Usernames are logged, passwords obviously never - the failure event carries
 * the rejected credential and it must not reach a log file.
 */
@Component
public class AuthenticationEventLogger {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationEventLogger.class);

    private final LoginAttemptService loginAttemptService;

    public AuthenticationEventLogger(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String address = loginAttemptService.currentClientAddress();
        loginAttemptService.recordSuccess(address);
        log.info("Login succeeded for '{}' from {}", event.getAuthentication().getName(), address);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String address = loginAttemptService.currentClientAddress();
        loginAttemptService.recordFailure(address);
        log.warn("Login failed for '{}' from {}: {}", event.getAuthentication().getName(), address,
                event.getException().getClass().getSimpleName());
    }
}
