package com.sykessec.calendarsync.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

/**
 * Sends a wrong code back to the code page, but an expired or missing pending
 * step back to the password page.
 *
 * The distinction matters because the two look identical to a user and have
 * opposite remedies: retyping the code cannot help once the pending record has
 * gone, and a user who is not told that will simply try again until they are
 * locked out by the throttle.
 */
public class SecondFactorFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof SecondFactorExpiredException) {
            setDefaultFailureUrl("/login?expired");
        } else {
            setDefaultFailureUrl(SecondFactorAuthenticationFilter.PROCESSING_URL + "?error");
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
