package com.sykessec.calendarsync.provider.msgraph;

import com.microsoft.aad.msal4j.AuthorizationCodeParameters;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.ITokenCacheAccessAspect;
import com.microsoft.aad.msal4j.ITokenCacheAccessContext;
import com.microsoft.aad.msal4j.SilentParameters;
import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * MSAL4J models refresh tokens differently to Google's client library: there
 * is no bare "refresh token" string to hold onto, only a serialized token
 * cache (a JSON blob MSAL manages internally) that must be persisted and
 * reloaded around every silent token acquisition. That serialized cache is
 * what calendar_connection.encrypted_credentials holds for MS_GRAPH
 * connections (Stage 0/1 convention: plain UTF-8 bytes, real encryption in
 * Stage 4).
 */
@Service
public class MsGraphOAuthService {

    private static final Set<String> SCOPES = Set.of(
            "https://graph.microsoft.com/Calendars.ReadWrite", "offline_access");
    private static final String CALLBACK_PATH = "/oauth2/microsoft/callback";

    private final AppProperties appProperties;

    public MsGraphOAuthService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public String redirectUri() {
        return appProperties.getBaseUrl() + CALLBACK_PATH;
    }

    public String buildAuthorizationUrl(String state) throws ProviderException {
        requireConfigured();
        String scope = String.join(" ", SCOPES);
        return "https://login.microsoftonline.com/" + tenantId() + "/oauth2/v2.0/authorize"
                + "?client_id=" + encode(appProperties.getMicrosoft().getClientId())
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri())
                + "&response_mode=query"
                + "&scope=" + encode(scope)
                + "&state=" + encode(state);
    }

    /** Exchanges an authorization code for tokens; returns the serialized token cache to persist. */
    public String exchangeCodeForTokenCache(String code) throws ProviderException {
        requireConfigured();
        StringBuilder cacheHolder = new StringBuilder();
        ConfidentialClientApplication app = buildApp(cacheHolder);
        try {
            AuthorizationCodeParameters params = AuthorizationCodeParameters
                    .builder(code, URI.create(redirectUri()))
                    .scopes(SCOPES)
                    .build();
            app.acquireToken(params).get();
            return app.tokenCache().serialize();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted exchanging Microsoft authorization code", e);
        } catch (ExecutionException e) {
            throw new ProviderException("Failed to exchange Microsoft authorization code: " + e.getMessage(), e);
        }
    }

    public record TokenResult(String accessToken, java.util.Date expiresOn, String updatedTokenCache) {
    }

    /** Silent (cache-based) token acquisition; the returned cache must be persisted back if it changed. */
    public TokenResult accessTokenFor(String serializedTokenCache) throws ProviderException {
        requireConfigured();
        StringBuilder cacheHolder = new StringBuilder(serializedTokenCache);
        ConfidentialClientApplication app = buildApp(cacheHolder);
        try {
            Set<IAccount> accounts = app.getAccounts().get();
            IAccount account = accounts.stream().findFirst()
                    .orElseThrow(() -> new ProviderException(
                            "No account found in the stored Microsoft token cache - reconnect this connection"));

            SilentParameters params = SilentParameters.builder(SCOPES, account).build();
            IAuthenticationResult result = app.acquireTokenSilently(params).get();
            return new TokenResult(result.accessToken(), result.expiresOnDate(), cacheHolder.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Interrupted acquiring Microsoft access token", e);
        } catch (ExecutionException | MalformedURLException e) {
            throw new ProviderException("Failed to acquire Microsoft access token: " + e.getMessage(), e);
        }
    }

    private ConfidentialClientApplication buildApp(StringBuilder cacheHolder) throws ProviderException {
        try {
            return ConfidentialClientApplication.builder(
                            appProperties.getMicrosoft().getClientId(),
                            ClientCredentialFactory.createFromSecret(appProperties.getMicrosoft().getClientSecret()))
                    .authority("https://login.microsoftonline.com/" + tenantId())
                    .setTokenCacheAccessAspect(new ITokenCacheAccessAspect() {
                        @Override
                        public void beforeCacheAccess(ITokenCacheAccessContext context) {
                            if (!cacheHolder.isEmpty()) {
                                context.tokenCache().deserialize(cacheHolder.toString());
                            }
                        }

                        @Override
                        public void afterCacheAccess(ITokenCacheAccessContext context) {
                            if (context.hasCacheChanged()) {
                                cacheHolder.setLength(0);
                                cacheHolder.append(context.tokenCache().serialize());
                            }
                        }
                    })
                    .build();
        } catch (MalformedURLException e) {
            throw new ProviderException("Invalid Microsoft authority URL: " + e.getMessage(), e);
        }
    }

    private String tenantId() {
        String tenant = appProperties.getMicrosoft().getTenantId();
        return tenant == null || tenant.isBlank() ? "common" : tenant;
    }

    private void requireConfigured() throws ProviderException {
        if (isBlank(appProperties.getMicrosoft().getClientId()) || isBlank(appProperties.getMicrosoft().getClientSecret())) {
            throw new ProviderException("Microsoft OAuth is not configured - set MS_OAUTH_CLIENT_ID and "
                    + "MS_OAUTH_CLIENT_SECRET (see README for how to register an Azure AD app)");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
