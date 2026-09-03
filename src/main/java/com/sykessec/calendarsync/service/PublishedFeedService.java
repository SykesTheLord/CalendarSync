package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.PublishedFeedSource;
import com.sykessec.calendarsync.ics.ExportProfile;
import com.sykessec.calendarsync.ics.IcsExportService;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import com.sykessec.calendarsync.repository.PublishedFeedSourceRepository;
import com.sykessec.calendarsync.security.CurrentUser;
import com.sykessec.calendarsync.util.TokenGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Backs both the filtered-feed flow (user picks sources, then adds rules
 * scoped to the resulting feed) and quick-publish (one click from the
 * Calendars view) - both build on the same published_feed/published_feed_source
 * mechanism, quick-publish is just a fast path that creates a single-source,
 * no-rules feed rather than a separate mechanism.
 */
@Service
public class PublishedFeedService {

    private final PublishedFeedRepository feedRepository;
    private final PublishedFeedSourceRepository sourceRepository;
    private final CalendarRepository calendarRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final IcsExportService icsExportService;
    private final CurrentUser currentUser;

    public PublishedFeedService(PublishedFeedRepository feedRepository, PublishedFeedSourceRepository sourceRepository,
                                 CalendarRepository calendarRepository, CalendarConnectionRepository connectionRepository,
                                 IcsExportService icsExportService, CurrentUser currentUser) {
        this.feedRepository = feedRepository;
        this.sourceRepository = sourceRepository;
        this.calendarRepository = calendarRepository;
        this.connectionRepository = connectionRepository;
        this.icsExportService = icsExportService;
        this.currentUser = currentUser;
    }

    public List<PublishedFeed> listForCurrentUser() {
        return feedRepository.findAllByUserId(currentUser.id());
    }

    public List<CalendarEntity> sourcesFor(Long feedId) {
        return sourceRepository.findAllByPublishedFeed(feedId).stream()
                .map(s -> calendarRepository.findById(s.getCalendar()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public PublishedFeed create(String name, List<Long> calendarIds) {
        return create(name, calendarIds, ExportProfile.DEFAULT);
    }

    @Transactional
    public PublishedFeed create(String name, List<Long> calendarIds, ExportProfile profile) {
        if (calendarIds.isEmpty()) {
            throw new IllegalArgumentException("A published feed needs at least one source calendar");
        }
        for (Long calendarId : calendarIds) {
            calendarRepository.findByIdAndUserId(calendarId, currentUser.id())
                    .orElseThrow(() -> new IllegalArgumentException("Not your calendar: " + calendarId));
        }

        PublishedFeed feed = new PublishedFeed();
        feed.setUserId(currentUser.id());
        feed.setName(name);
        feed.setAccessToken(TokenGenerator.urlSafeToken(24));
        feed.setCacheTtlSeconds(3600);
        (profile == null ? ExportProfile.DEFAULT : profile).applyTo(feed);
        feed = feedRepository.save(feed);

        for (Long calendarId : calendarIds) {
            sourceRepository.save(new PublishedFeedSource(feed.getId(), calendarId));
        }
        return feed;
    }

    /**
     * Reuses an existing feed if one already has this calendar as its ONLY
     * source, rather than creating a duplicate, per spec. Read-only
     * calendars can be quick-published too - publishing only ever reads.
     */
    @Transactional
    public PublishedFeed quickPublish(Long calendarId) {
        CalendarEntity calendar = calendarRepository.findByIdAndUserId(calendarId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your calendar: " + calendarId));

        for (PublishedFeed feed : listForCurrentUser()) {
            List<CalendarEntity> sources = sourcesFor(feed.getId());
            if (sources.size() == 1 && sources.get(0).getId().equals(calendarId)) {
                return feed;
            }
        }

        CalendarConnection connection = connectionRepository.findById(calendar.getConnectionId()).orElse(null);
        String name = (connection == null ? "Unknown" : connection.getDisplayName()) + " – " + calendar.getName();
        return create(name, List.of(calendarId));
    }

    public void rotateToken(Long feedId) {
        PublishedFeed feed = feedRepository.findByIdAndUserId(feedId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your feed: " + feedId));
        feed.setAccessToken(TokenGenerator.urlSafeToken(24));
        feedRepository.save(feed);
        icsExportService.invalidate(feedId);
    }

    /**
     * Renames a feed and replaces its source set in one step. Sources could
     * only ever be chosen at creation time, so a feed that needed one more
     * calendar - or that lost its sources when a connection was deleted - had
     * to be deleted and rebuilt, which changes the token and breaks every
     * subscriber. The cache is invalidated because the answer the feed serves
     * changes the moment its sources do.
     */
    @Transactional
    public PublishedFeed update(Long feedId, String name, List<Long> calendarIds) {
        return update(feedId, name, calendarIds, null);
    }

    /**
     * The export-settings overload. A null profile leaves the feed's current
     * settings alone rather than resetting them to the defaults - callers that
     * only know about names and sources (and the two-argument overload above)
     * must not silently wipe a profile they never saw.
     */
    @Transactional
    public PublishedFeed update(Long feedId, String name, List<Long> calendarIds, ExportProfile profile) {
        PublishedFeed feed = feedRepository.findByIdAndUserId(feedId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your feed: " + feedId));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A published feed needs a name");
        }
        if (calendarIds.isEmpty()) {
            throw new IllegalArgumentException("A published feed needs at least one source calendar");
        }
        for (Long calendarId : calendarIds) {
            calendarRepository.findByIdAndUserId(calendarId, currentUser.id())
                    .orElseThrow(() -> new IllegalArgumentException("Not your calendar: " + calendarId));
        }

        feed.setName(name.trim());
        if (profile != null) {
            profile.applyTo(feed);
        }
        feed = feedRepository.save(feed);

        sourceRepository.deleteAll(sourceRepository.findAllByPublishedFeed(feedId));
        for (Long calendarId : calendarIds) {
            sourceRepository.save(new PublishedFeedSource(feedId, calendarId));
        }
        icsExportService.invalidate(feedId);
        return feed;
    }

    /**
     * Changes only how the feed's events are written out - privacy, busy status
     * and reminders - without touching its name, sources or token. Separate
     * from update() because this is the one change a subscriber sees without
     * the feed's contents changing at all, and because it is the natural unit
     * for the presets in the UI.
     *
     * The cache is invalidated for the same reason update() does it: the bytes
     * already cached were built under the old profile, and a feed with an hour
     * of TTL left would otherwise keep serving them long after the setting
     * looked applied.
     */
    @Transactional
    public PublishedFeed updateExportProfile(Long feedId, ExportProfile profile) {
        PublishedFeed feed = feedRepository.findByIdAndUserId(feedId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your feed: " + feedId));
        (profile == null ? ExportProfile.DEFAULT : profile).applyTo(feed);
        feed = feedRepository.save(feed);
        icsExportService.invalidate(feedId);
        return feed;
    }

    @Transactional
    public void delete(Long feedId) {
        feedRepository.findByIdAndUserId(feedId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your feed: " + feedId));
        icsExportService.invalidate(feedId);
        feedRepository.deleteById(feedId);
    }

    public String subscribeUrl(PublishedFeed feed, String baseUrl) {
        return baseUrl + "/feed/" + feed.getAccessToken() + ".ics";
    }
}
