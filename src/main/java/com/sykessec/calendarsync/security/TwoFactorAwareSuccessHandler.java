package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.vaadin.flow.spring.security.VaadinSavedRequestAwareAuthenticationSuccessHandler;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import java.io.IOException;

/**
 * Splits the login into two steps for users who have a second factor.
 *
 * HOW THIS IS INSTALLED, AND WHY IT LOOKS ODD. VaadinSecurityConfigurer builds
 * the form-login configuration itself, in its init(), which runs when
 * http.build() is called - after the body of SecurityConfig's filterChain
 * bean. So calling http.formLogin(f -> f.successHandler(...)) there does
 * nothing: Vaadin overwrites it moments later. What Vaadin does do is resolve
 * its handler as
 *
 *     getSharedObject(VaadinSavedRequestAwareAuthenticationSuccessHandler.class)
 *         .orElseGet(this::createAuthenticationSuccessHandler)
 *
 * so registering an instance of that type as a shared object is the supported
 * way in. SecurityConfig does exactly that.
 *
 * The consequence to remember: because the shared object is present, Vaadin
 * never runs createAuthenticationSuccessHandler(), which is what would
 * normally have applied defaultSuccessUrl(...) and the request cache. Both
 * therefore have to be set on this instance instead, and
 * VaadinSecurityConfigurer.defaultSuccessUrl() would be dead configuration if
 * it were still there. SecurityConfig sets them here and says so.
 *
 * WHAT IT DOES. On a successful password check it re-reads the user (the
 * principal is a snapshot taken at load time and this class must not act on a
 * stale totpEnabled), and for a user with a second factor it destroys the
 * session Spring has just authenticated, opens a fresh one holding nothing but
 * a PendingSecondFactor, and sends them to the code page.
 *
 * Destroying the session rather than clearing SecurityContextHolder is the
 * important part. AbstractAuthenticationProcessingFilter has ALREADY written
 * the SecurityContext through the SecurityContextRepository by the time a
 * success handler runs, so clearing the holder would leave a fully
 * authenticated session persisted on the server while the user is looking at a
 * "please enter your code" page - the second factor would be a formality
 * anyone could skip by requesting a different URL. Invalidating the session
 * takes the stored context with it, and rotates the session id into the
 * bargain.
 */
public class TwoFactorAwareSuccessHandler extends VaadinSavedRequestAwareAuthenticationSuccessHandler {

    /** Where a user who still owes a second factor is sent. */
    public static final String VERIFY_PATH = "/login/verify";

    // A user who is REQUIRED to enrol but has not is deliberately not diverted
    // here: they authenticate normally and ForcedEnrolmentInitializer routes
    // them on their first navigation. Keeping that in one place means the two
    // mechanisms cannot disagree about who is allowed where.

    private static final Logger log = LoggerFactory.getLogger(TwoFactorAwareSuccessHandler.class);

    private final AppUserRepository appUserRepository;

    public TwoFactorAwareSuccessHandler(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        AppUser user = resolveUser(authentication);

        if (user == null || !user.isTotpEnabled()) {
            // No second factor, or a user we cannot resolve: behave exactly as
            // the stock Vaadin handler, which is what every existing account
            // gets after an upgrade.
            super.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        String targetUrl = determineTargetUrl(request, response);

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession fresh = request.getSession(true);
        fresh.setAttribute(PendingSecondFactor.SESSION_ATTRIBUTE,
                PendingSecondFactor.forUser(user.getId(), user.getUsername(), targetUrl));

        log.debug("Password accepted for '{}', awaiting second factor", user.getUsername());
        getRedirectStrategy().sendRedirect(request, response, VERIFY_PATH);
    }

    private AppUser resolveUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return null;
        }
        return appUserRepository.findById(principal.getId()).orElse(null);
    }
}
