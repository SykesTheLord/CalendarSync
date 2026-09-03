package com.sykessec.calendarsync.provider.google;

import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.service.CalendarConnectionService;
import com.sykessec.calendarsync.util.TokenGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Plain Spring MVC, not a Vaadin route - this is a redirect dance with
 * Google, not a UI page. Governed by ordinary Spring Security (the user
 * must already be logged into CalendarSync in this browser session; only
 * /feed/** is the deliberate unauthenticated exception in this app).
 */
@RestController
@RequestMapping("/oauth2/google")
public class GoogleOAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthCallbackController.class);
    private static final String STATE_SESSION_KEY = "google_oauth_state";

    private final GoogleOAuthService oAuthService;
    private final CalendarConnectionService connectionService;

    public GoogleOAuthCallbackController(GoogleOAuthService oAuthService, CalendarConnectionService connectionService) {
        this.oAuthService = oAuthService;
        this.connectionService = connectionService;
    }

    @GetMapping("/authorize")
    public void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String state = TokenGenerator.urlSafeToken(16);
            request.getSession(true).setAttribute(STATE_SESSION_KEY, state);
            response.sendRedirect(oAuthService.buildAuthorizationUrl(state));
        } catch (ProviderException e) {
            redirectWithError(response, e.getMessage());
        }
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                          @RequestParam(required = false) String state,
                          @RequestParam(required = false) String error,
                          HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        String expectedState = session == null ? null : (String) session.getAttribute(STATE_SESSION_KEY);
        if (session != null) {
            session.removeAttribute(STATE_SESSION_KEY);
        }

        if (error != null) {
            redirectWithError(response, "Google denied access: " + error);
            return;
        }
        if (code == null || expectedState == null || !expectedState.equals(state)) {
            redirectWithError(response, "OAuth state mismatch - please try connecting again");
            return;
        }

        try {
            String refreshToken = oAuthService.exchangeCodeForRefreshToken(code);
            connectionService.create(ProviderType.GOOGLE, "Google Calendar", "oauth2", refreshToken, null);
            response.sendRedirect("/connections");
        } catch (ProviderException e) {
            log.warn("Google OAuth callback failed: {}", e.getMessage());
            redirectWithError(response, e.getMessage());
        }
    }

    private void redirectWithError(HttpServletResponse response, String message) throws IOException {
        response.sendRedirect("/connections?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }
}
