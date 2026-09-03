package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.AbstractIntegrationTest;
import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import com.sykessec.calendarsync.service.UserAdminService;
import com.sykessec.calendarsync.util.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.session.SessionRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disabling an account has to cut off access that is already in flight, not
 * just the next login attempt. Two paths bypass the "check enabled at login"
 * assumption entirely: a session the user is already holding, and
 * /feed/{token}.ics, which never touches Spring Security at all.
 *
 * Deliberately not @Transactional: the feed request arrives over real HTTP on
 * another thread, so it only sees committed rows.
 */
class AccountDeactivationTest extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private PublishedFeedRepository feedRepository;
    @Autowired
    private UserAdminService userAdminService;
    @Autowired
    private SessionRegistry sessionRegistry;
    @LocalServerPort
    private int port;

    @Test
    void disablingAUserExpiresTheirLiveSessions() {
        AppUser user = createUser("session-holder");
        AppUserPrincipal principal = new AppUserPrincipal(user);
        sessionRegistry.registerNewSession("session-being-held", principal);

        assertThat(sessionRegistry.getSessionInformation("session-being-held").isExpired()).isFalse();

        userAdminService.setEnabled(user.getId(), false);

        assertThat(sessionRegistry.getSessionInformation("session-being-held").isExpired()).isTrue();
    }

    @Test
    void anAdminPasswordResetExpiresTheUsersLiveSessions() {
        AppUser user = createUser("reset-target");
        sessionRegistry.registerNewSession("session-to-revoke", new AppUserPrincipal(user));

        userAdminService.resetPassword(user.getId(), "a-brand-new-password");

        assertThat(sessionRegistry.getSessionInformation("session-to-revoke").isExpired()).isTrue();
    }

    @Test
    void leavesOtherUsersSessionsAlone() {
        AppUser target = createUser("the-disabled-one");
        AppUser bystander = createUser("the-bystander");
        sessionRegistry.registerNewSession("target-session", new AppUserPrincipal(target));
        sessionRegistry.registerNewSession("bystander-session", new AppUserPrincipal(bystander));

        userAdminService.setEnabled(target.getId(), false);

        assertThat(sessionRegistry.getSessionInformation("bystander-session").isExpired()).isFalse();
    }

    @Test
    void aDisabledUsersPublishedFeedStopsServing() {
        AppUser user = createUser("feed-owner");
        String token = TokenGenerator.urlSafeToken(24);

        PublishedFeed feed = new PublishedFeed();
        feed.setUserId(user.getId());
        feed.setName("Test feed");
        feed.setAccessToken(token);
        feed.setCacheTtlSeconds(3600);
        feedRepository.save(feed);

        assertThat(feedStatus(token)).isEqualTo(200);

        userAdminService.setEnabled(user.getId(), false);

        assertThat(feedStatus(token)).isEqualTo(404);
    }

    private int feedStatus(String token) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/feed/" + token + ".ics"))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private AppUser createUser(String username) {
        // Unique per run: this test commits, and the SQLite file is shared by
        // every test class in the JVM.
        AppUser user = new AppUser();
        user.setUsername(username + "-" + TokenGenerator.urlSafeToken(6));
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(Role.USER);
        user.setEnabled(true);
        return appUserRepository.save(user);
    }
}
