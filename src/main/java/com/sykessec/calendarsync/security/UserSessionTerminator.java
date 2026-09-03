package com.sykessec.calendarsync.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Ends every live session belonging to one account.
 *
 * Spring Security checks UserDetails.isEnabled() when a session is created and
 * never again - the authenticated principal then lives in the HttpSession. So
 * "disable this user" in the admin view stopped them logging in *next* time
 * while leaving whatever session they already had fully working, which is
 * exactly backwards for the case disabling exists to handle (an account you
 * believe is in the wrong hands, right now).
 */
@Component
public class UserSessionTerminator {

    private static final Logger log = LoggerFactory.getLogger(UserSessionTerminator.class);

    private final SessionRegistry sessionRegistry;

    public UserSessionTerminator(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Matched on the application's own user id rather than on the principal
     * object: AppUserPrincipal doesn't implement equals(), so the registry's
     * by-principal lookup would miss every session it was asked about.
     */
    public void terminateSessionsFor(Long userId) {
        if (userId == null) {
            return;
        }
        int expired = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof AppUserPrincipal appUser) || !userId.equals(appUser.getId())) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                session.expireNow();
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} active session(s) for user id {}", expired, userId);
        }
    }
}
