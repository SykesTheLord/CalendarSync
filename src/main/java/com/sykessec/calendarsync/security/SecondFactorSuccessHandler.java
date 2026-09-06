package com.sykessec.calendarsync.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * Lands a user who has just completed the second factor where they were
 * originally going.
 *
 * It cannot use the saved-request machinery the ordinary login path uses,
 * because TwoFactorAwareSuccessHandler invalidated the session between the two
 * steps and the saved request went with it. The target URL was copied into the
 * PendingSecondFactor record for exactly this moment.
 *
 * The pending record is read before the filter's session-fixation strategy has
 * finished with the session, so it is fetched defensively and falls back to the
 * default rather than failing a login that has already succeeded.
 */
public class SecondFactorSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    public SecondFactorSuccessHandler() {
        super("/connections");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(PendingSecondFactor.SESSION_ATTRIBUTE);
        if (session != null) {
            session.removeAttribute(PendingSecondFactor.SESSION_ATTRIBUTE);
        }

        if (pending instanceof PendingSecondFactor record
                && record.targetUrl() != null
                && !record.targetUrl().isBlank()) {
            getRedirectStrategy().sendRedirect(request, response, record.targetUrl());
            return;
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
