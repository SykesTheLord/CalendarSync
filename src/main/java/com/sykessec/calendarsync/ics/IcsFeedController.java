package com.sykessec.calendarsync.ics;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain Spring MVC, not a Vaadin route - this is a machine-to-machine
 * endpoint for calendar clients (Proton etc.), not a UI page. Deliberately
 * unauthenticated by design (permitted in SecurityConfig): the token in the
 * URL is the credential, since these clients can't do interactive login.
 * The app's normal Spring Security session auth governs everything else.
 */
@RestController
public class IcsFeedController {

    private final PublishedFeedRepository feedRepository;
    private final AppUserRepository userRepository;
    private final IcsExportService exportService;

    public IcsFeedController(PublishedFeedRepository feedRepository, AppUserRepository userRepository,
                              IcsExportService exportService) {
        this.feedRepository = feedRepository;
        this.userRepository = userRepository;
        this.exportService = exportService;
    }

    @GetMapping(value = "/feed/{token}.ics")
    public ResponseEntity<byte[]> feed(@PathVariable String token) {
        PublishedFeed feed = feedRepository.findByAccessToken(token).orElse(null);
        if (feed == null) {
            return ResponseEntity.notFound().build();
        }

        // This is the one path into the application that never touches Spring
        // Security, so the owning account's status has to be checked by hand.
        // Otherwise disabling a user leaves every feed they ever published
        // still serving their calendar data to anyone holding the token.
        // 404 rather than 403: an unauthenticated caller learns nothing about
        // whether the token was real.
        AppUser owner = feed.getUserId() == null ? null : userRepository.findById(feed.getUserId()).orElse(null);
        if (owner == null || !owner.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] body = exportService.getOrRegenerate(feed);
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("text/calendar;charset=utf-8"))
                    .body(body);
        } catch (ProviderException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
