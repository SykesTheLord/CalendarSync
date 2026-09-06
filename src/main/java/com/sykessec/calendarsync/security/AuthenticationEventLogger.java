package com.sykessec.calendarsync.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
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
        Authentication authentication = event.getAuthentication();

        // Only a login that is actually FINISHED clears the failure counter.
        // With a second factor configured, the password step also publishes a
        // success event, and treating that as a completed login would reset the
        // counter on every attempt - leaving an attacker who already has the
        // password free to guess six-digit codes one per login, forever,
        // without ever reaching MAX_FAILURES. The throttle would still look
        // green while protecting nothing, which is the exact failure this
        // class exists to prevent.
        if (isComplete(authentication)) {
            loginAttemptService.recordSuccess(address);
            log.info("Login succeeded for '{}' from {}", authentication.getName(), address);
        } else {
            log.info("Password accepted for '{}' from {}, awaiting second factor",
                    authentication.getName(), address);
        }
    }

    private boolean isComplete(Authentication authentication) {
        if (authentication instanceof SecondFactorCompletedAuthentication) {
            return true;
        }
        // Read off the principal's snapshot rather than the database: it was
        // loaded moments ago by this same login, and a query here would run on
        // every authentication in the application.
        return !(authentication.getPrincipal() instanceof AppUserPrincipal principal)
                || !principal.getUser().isTotpEnabled();
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String address = loginAttemptService.currentClientAddress();
        loginAttemptService.recordFailure(address);
        log.warn("Login failed for '{}' from {}: {}", event.getAuthentication().getName(), address,
                event.getException().getClass().getSimpleName());
    }
}
