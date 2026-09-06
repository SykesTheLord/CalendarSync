package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.ui.account.TwoFactorSetupView;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Routes a user an administrator has marked as required, but who has not
 * enrolled yet, to the setup view before they can reach anything else.
 *
 * Registered as a UI-wide BeforeEnterListener rather than a check inside
 * MainLayout, because a check in a layout only runs when that layout is
 * constructed - navigating between two views that share it would skip it
 * entirely, and typing a URL directly would be a coin toss.
 *
 * BE CLEAR ABOUT WHAT THIS IS. It is a prompt, not a containment boundary. A
 * user in this state has given the correct password and IS fully
 * authenticated: /feed tokens they already hold keep working, and a non-Vaadin
 * endpoint such as the OAuth callback is still reachable by typing its URL.
 * Making it a real boundary would mean withholding authentication until
 * enrolment completes, which locks people out of the very page that would fix
 * it. The security boundary in this feature is the second factor demanded of
 * users who HAVE enrolled, and that one is enforced in the authentication
 * layer where it cannot be walked around. This exists so that "required"
 * actually gets people enrolled.
 */
@Component
public class ForcedEnrolmentInitializer implements VaadinServiceInitListener {

    private final AppUserRepository appUserRepository;

    public ForcedEnrolmentInitializer(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiEvent ->
                uiEvent.getUI().addBeforeEnterListener(enterEvent -> {
                    if (shouldForceEnrolment(enterEvent.getNavigationTarget(), currentUser())) {
                        enterEvent.forwardTo(TwoFactorSetupView.class);
                    }
                }));
    }

    /**
     * The whole decision, separated from the Vaadin plumbing so it can be
     * tested without a browser or a router.
     *
     * Letting the setup view itself through is not a detail - without that
     * exemption this forwards the user to a page which immediately forwards
     * them again, and the account becomes unusable rather than merely nagged.
     */
    static boolean shouldForceEnrolment(Class<?> navigationTarget, AppUser user) {
        if (navigationTarget == TwoFactorSetupView.class) {
            return false;
        }
        return user != null && user.isTotpRequired() && !user.isTotpEnabled();
    }

    /**
     * Read fresh rather than from the principal's snapshot: an administrator
     * flipping the flag should take effect on the user's next navigation, not
     * only after they sign in again.
     */
    private AppUser currentUser() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() == null
                || !(context.getAuthentication().getPrincipal() instanceof AppUserPrincipal principal)) {
            return null;
        }
        return appUserRepository.findById(principal.getId()).orElse(null);
    }
}
