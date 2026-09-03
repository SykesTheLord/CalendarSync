package com.sykessec.calendarsync.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The one place every service goes to find "who is asking" - this is what
 * makes user_id scoping enforceable without threading a userId parameter
 * through every UI call site by hand.
 */
@Component
public class CurrentUser {

    public Long id() {
        return principal().getId();
    }

    public AppUserPrincipal principal() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof AppUserPrincipal appUserPrincipal)) {
            throw new IllegalStateException("No authenticated CalendarSync user in the security context");
        }
        return appUserPrincipal;
    }
}
