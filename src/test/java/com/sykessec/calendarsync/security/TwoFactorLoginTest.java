package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.AbstractIntegrationTest;
import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.service.TwoFactorService;
import com.sykessec.calendarsync.util.Base32;
import com.sykessec.calendarsync.util.TokenGenerator;
import com.sykessec.calendarsync.util.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second factor over real HTTP, because almost everything that could go
 * wrong here is in the filter chain rather than in the verification arithmetic
 * (which TotpTest already pins to the RFC's vectors).
 *
 * Deliberately not @Transactional and with unique usernames, for the same
 * reasons as AccountDeactivationTest: these requests are served on another
 * thread and only see committed rows, and the SQLite file is shared by every
 * test class in the JVM.
 */
class TwoFactorLoginTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private TwoFactorService twoFactorService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private LoginAttemptService loginAttemptService;
    @LocalServerPort
    private int port;

    @Test
    void aUserWithoutASecondFactorStillLogsStraightIn() {
        AppUser user = createUser("no-2fa");
        HttpClient client = newClient();

        HttpResponse<String> response = postLogin(client, user.getUsername(), PASSWORD);

        // Straight to the app, exactly as before this feature existed.
        assertThat(location(response)).contains("/connections");
    }

    @Test
    void aUserWithASecondFactorIsSentToTheCodePageAndIsNotYetAuthenticated() {
        Enrolled enrolled = createEnrolledUser("needs-code");
        HttpClient client = newClient();

        HttpResponse<String> login = postLogin(client, enrolled.username(), PASSWORD);
        assertThat(location(login)).endsWith("/login/verify");

        // The critical assertion: the password alone must not have produced a
        // usable session. If the success handler had merely cleared the
        // SecurityContextHolder instead of invalidating the session, the
        // already-persisted context would still be there and this would be a
        // 200 - the second factor would be skippable by typing a URL.
        HttpResponse<String> protectedPage = get(client, "/connections");
        assertThat(protectedPage.statusCode()).isEqualTo(302);
        assertThat(location(protectedPage)).contains("/login");
    }

    @Test
    void theRightCodeCompletesTheLogin() {
        Enrolled enrolled = createEnrolledUser("good-code");
        HttpClient client = newClient();
        postLogin(client, enrolled.username(), PASSWORD);

        HttpResponse<String> verify = postVerify(client, currentCode(enrolled.secret()), null);

        assertThat(location(verify)).contains("/connections");
        assertThat(get(client, "/connections").statusCode()).isEqualTo(200);
    }

    @Test
    void aWrongCodeDoesNotCompleteTheLogin() {
        Enrolled enrolled = createEnrolledUser("bad-code");
        HttpClient client = newClient();
        postLogin(client, enrolled.username(), PASSWORD);

        HttpResponse<String> verify = postVerify(client, "000000", null);

        assertThat(location(verify)).contains("/login/verify?error");
        assertThat(get(client, "/connections").statusCode()).isEqualTo(302);
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        Enrolled enrolled = createEnrolledUser("replay");
        String code = currentCode(enrolled.secret());

        HttpClient first = newClient();
        postLogin(first, enrolled.username(), PASSWORD);
        assertThat(location(postVerify(first, code, null))).contains("/connections");

        // Same code, same 30-second step, fresh login. It is still arithmetically
        // valid, so only the recorded last-step check can refuse it.
        HttpClient second = newClient();
        postLogin(second, enrolled.username(), PASSWORD);
        assertThat(location(postVerify(second, code, null))).contains("/login/verify?error");
    }

    @Test
    void aRecoveryCodeWorksOnceAndThenDoesNot() {
        Enrolled enrolled = createEnrolledUser("recovery");
        String recoveryCode = enrolled.recoveryCodes().get(0);

        HttpClient first = newClient();
        postLogin(first, enrolled.username(), PASSWORD);
        assertThat(location(postVerify(first, null, recoveryCode))).contains("/connections");

        HttpClient second = newClient();
        postLogin(second, enrolled.username(), PASSWORD);
        assertThat(location(postVerify(second, null, recoveryCode))).contains("/login/verify?error");
    }

    @Test
    void failedCodesFeedTheLoginThrottle() {
        // This is the test that catches the ProviderManager built without an
        // AuthenticationEventPublisher. Without one it publishes nothing,
        // AuthenticationEventLogger never hears, LoginAttemptService never
        // counts, and the second factor is an unthrottled six-digit guess -
        // while every other test in this class still passes.
        Enrolled enrolled = createEnrolledUser("throttle");
        loginAttemptService.recordSuccess("127.0.0.1");

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            HttpClient client = newClient();
            postLogin(client, enrolled.username(), PASSWORD);
            postVerify(client, "000000", null);
        }

        assertThat(loginAttemptService.isBlocked("127.0.0.1")).isTrue();
        loginAttemptService.recordSuccess("127.0.0.1");
    }

    // --- helpers ---------------------------------------------------------

    private record Enrolled(String username, String secret, List<String> recoveryCodes) {
    }

    /**
     * Enrols with the PREVIOUS step's code on purpose. Confirming an enrolment
     * consumes the step it used - that is the replay protection doing its job -
     * so enrolling with the current code would leave these tests unable to sign
     * in with it a moment later, which is correct behaviour and not what they
     * are trying to exercise. A user typing the last code before it rolls over
     * produces exactly this state.
     */
    private Enrolled createEnrolledUser(String prefix) {
        AppUser user = createUser(prefix);
        String secret = Totp.generateSecret();
        long previousStep = Totp.timeStep(Instant.now().getEpochSecond()) - 1;
        String code = Totp.codeAt(Base32.decode(secret), previousStep);
        List<String> codes = twoFactorService.enable(user.getId(), secret, code);
        return new Enrolled(user.getUsername(), secret, codes);
    }

    private AppUser createUser(String prefix) {
        AppUser user = new AppUser();
        user.setUsername(prefix + "-" + TokenGenerator.urlSafeToken(6));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        return appUserRepository.save(user);
    }

    private static String currentCode(String secret) {
        return Totp.codeAt(Base32.decode(secret), Totp.timeStep(Instant.now().getEpochSecond()));
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HttpResponse<String> postLogin(HttpClient client, String username, String password) {
        return send(client, HttpRequest.newBuilder()
                .uri(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "username=" + enc(username) + "&password=" + enc(password))));
    }

    /**
     * Fetches the verify page first, so the form's CSRF token (and the session
     * cookie it is bound to) are the ones actually submitted - the same thing a
     * browser does, and the only way this exercises the real token check rather
     * than an exemption.
     */
    private HttpResponse<String> postVerify(HttpClient client, String code, String recoveryCode) {
        HttpResponse<String> page = get(client, "/login/verify");
        Map.Entry<String, String> csrf = extractCsrf(page.body());

        StringBuilder body = new StringBuilder();
        if (csrf != null) {
            body.append(enc(csrf.getKey())).append('=').append(enc(csrf.getValue()));
        }
        if (code != null) {
            body.append(body.isEmpty() ? "" : "&").append("code=").append(enc(code));
        }
        if (recoveryCode != null) {
            body.append(body.isEmpty() ? "" : "&").append("recoveryCode=").append(enc(recoveryCode));
        }

        return send(client, HttpRequest.newBuilder()
                .uri(uri("/login/verify"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    /**
     * Reads Spring Security's CSRF token out of the &lt;meta name="_csrf"&gt; tag
     * Vaadin writes into every bootstrap page.
     *
     * The hidden input TwoFactorVerifyView renders is not visible here, and
     * that is expected rather than a bug: a Vaadin view is built client-side,
     * so the form only exists in the DOM after the browser has run the client
     * engine. A plain HTTP client never gets that far. The meta tag carries the
     * same session token the form field would, so posting it exercises the real
     * CSRF check rather than routing around it.
     */
    private static Map.Entry<String, String> extractCsrf(String html) {
        Matcher name = Pattern.compile("<meta name=\"_csrf_parameter\" content=\"([^\"]+)\"").matcher(html);
        Matcher value = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"").matcher(html);
        if (value.find()) {
            return Map.entry(name.find() ? name.group(1) : "_csrf", value.group(1));
        }
        return null;
    }

    private HttpResponse<String> get(HttpClient client, String path) {
        return send(client, HttpRequest.newBuilder().uri(uri(path)).GET());
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder) {
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
