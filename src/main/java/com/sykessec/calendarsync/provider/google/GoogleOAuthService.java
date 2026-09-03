package com.sykessec.calendarsync.provider.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * OAuth is used only for talking to Google as a calendar provider - a
 * separate concern from the app's own local-account authentication
 * (Spring Security / app_user). access_type=offline + a forced consent
 * prompt is what actually gets a refresh token back on every connect, not
 * just the first one.
 */
@Service
public class GoogleOAuthService {

    private static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/calendar");
    private static final String CALLBACK_PATH = "/oauth2/google/callback";

    private final AppProperties appProperties;
    private final HttpTransport httpTransport;
    private final JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    public GoogleOAuthService(AppProperties appProperties) throws GeneralSecurityException, IOException {
        this.appProperties = appProperties;
        this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    }

    public String redirectUri() {
        return appProperties.getBaseUrl() + CALLBACK_PATH;
    }

    public String buildAuthorizationUrl(String state) throws ProviderException {
        requireConfigured();
        GoogleAuthorizationCodeFlow flow = flow();
        GoogleAuthorizationCodeRequestUrl url = flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri())
                .setAccessType("offline")
                .setState(state);
        url.set("prompt", "consent");
        return url.build();
    }

    /** Exchanges an authorization code for tokens; returns the refresh token to persist. */
    public String exchangeCodeForRefreshToken(String code) throws ProviderException {
        requireConfigured();
        try {
            var tokenResponse = flow().newTokenRequest(code)
                    .setRedirectUri(redirectUri())
                    .execute();
            String refreshToken = tokenResponse.getRefreshToken();
            if (refreshToken == null) {
                throw new ProviderException("Google did not return a refresh token - the user may need to "
                        + "revoke prior access at https://myaccount.google.com/permissions and reconnect");
            }
            return refreshToken;
        } catch (IOException e) {
            throw new ProviderException("Failed to exchange Google authorization code: " + e.getMessage(), e);
        }
    }

    /** A ready-to-use Credential (auto-refreshing access token) from a stored refresh token. */
    public GoogleCredential credentialFor(String refreshToken) throws ProviderException {
        requireConfigured();
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(httpTransport)
                .setJsonFactory(jsonFactory)
                .setClientSecrets(appProperties.getGoogle().getClientId(), appProperties.getGoogle().getClientSecret())
                .build();
        credential.setRefreshToken(refreshToken);
        try {
            if (!credential.refreshToken()) {
                throw new ProviderException("Google rejected the refresh token - the connection needs to be re-authorized");
            }
        } catch (IOException e) {
            throw new ProviderException("Failed to refresh Google access token: " + e.getMessage(), e);
        }
        return credential;
    }

    public HttpTransport httpTransport() {
        return httpTransport;
    }

    public JsonFactory jsonFactory() {
        return jsonFactory;
    }

    private GoogleAuthorizationCodeFlow flow() throws ProviderException {
        requireConfigured();
        try {
            return new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory,
                    appProperties.getGoogle().getClientId(), appProperties.getGoogle().getClientSecret(), SCOPES)
                    .setAccessType("offline")
                    .build();
        } catch (RuntimeException e) {
            throw new ProviderException("Failed to build Google OAuth flow: " + e.getMessage(), e);
        }
    }

    private void requireConfigured() throws ProviderException {
        if (isBlank(appProperties.getGoogle().getClientId()) || isBlank(appProperties.getGoogle().getClientSecret())) {
            throw new ProviderException("Google OAuth is not configured - set GOOGLE_OAUTH_CLIENT_ID and "
                    + "GOOGLE_OAUTH_CLIENT_SECRET (see README for how to register an OAuth app)");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
