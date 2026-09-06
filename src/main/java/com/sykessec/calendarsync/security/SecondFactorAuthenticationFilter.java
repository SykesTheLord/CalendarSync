package com.sykessec.calendarsync.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Processes the code step. Extending AbstractAuthenticationProcessingFilter
 * rather than handling the POST in a controller or a Vaadin listener is what
 * gets, without writing any of it: SecurityContext persistence, session-fixation
 * rotation at the moment authentication actually completes, and delegation to a
 * ProviderManager that publishes the events LoginAttemptService needs.
 *
 * The pending record is consumed here, before verification is attempted, so a
 * submitted code can only ever be checked once per password step. Without that
 * the pending session would be a standing permit to keep guessing, which is
 * precisely what the rate limiter is trying to prevent.
 */
public class SecondFactorAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    public static final String PROCESSING_URL = "/login/verify";

    public SecondFactorAuthenticationFilter(RequestMatcher matcher, AuthenticationManager authenticationManager) {
        super(matcher, authenticationManager);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        HttpSession session = request.getSession(false);
        PendingSecondFactor pending = session == null ? null
                : (PendingSecondFactor) session.getAttribute(PendingSecondFactor.SESSION_ATTRIBUTE);

        if (pending == null || pending.isExpired()) {
            if (session != null) {
                session.removeAttribute(PendingSecondFactor.SESSION_ATTRIBUTE);
            }
            // Distinguished from a wrong code by its type, so the failure
            // handler can send the user back to the password page rather than
            // to a code page that has nothing left to verify against.
            throw new SecondFactorExpiredException("Sign in again - the verification step timed out");
        }

        String code = trimToNull(request.getParameter("code"));
        String recoveryCode = trimToNull(request.getParameter("recoveryCode"));
        if (code == null && recoveryCode == null) {
            throw new InsufficientAuthenticationException("Enter the code from your authenticator");
        }

        // One attempt per pending record. A new password step is needed to get
        // another, which is what stops this endpoint being an oracle.
        session.removeAttribute(PendingSecondFactor.SESSION_ATTRIBUTE);

        return getAuthenticationManager().authenticate(
                new SecondFactorAuthenticationToken(pending.userId(), code, recoveryCode));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
