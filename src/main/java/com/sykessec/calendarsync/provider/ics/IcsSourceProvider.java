package com.sykessec.calendarsync.provider.ics;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.ics.IcsCalendarMapper;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.DiscoveredCalendar;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only: never writes back to the source URL, per spec. "Deletion" of
 * an ICS-sourced event only ever means exclusion from a published_feed
 * (handled entirely by IcsExportService), never a call on this class -
 * deleteEvent/createEvent both refuse outright so a coding mistake
 * elsewhere can't accidentally attempt a real write against a read-only
 * source.
 *
 * A 304 response means "unchanged since last poll," but IcsExportService
 * still needs the full current event set on every feed regeneration (not
 * just a diff) - lastKnownEventsByCalendarId is an in-memory cache (not
 * persisted; rebuilds on next 200 after a restart) serving exactly that,
 * kept separate from sync_state's conditional-GET bookkeeping.
 */
@Service
public class IcsSourceProvider implements CalendarProvider {

    private final RestClient restClient;
    private final IcsCalendarMapper mapper;
    private final Map<Long, List<ProviderEvent>> lastKnownEventsByCalendarId = new ConcurrentHashMap<>();

    public IcsSourceProvider(RestClient.Builder restClientBuilder, IcsCalendarMapper mapper) {
        this.restClient = restClientBuilder.build();
        this.mapper = mapper;
    }

    @Override
    public boolean supports(ProviderType type) {
        return type == ProviderType.ICS_SOURCE;
    }

    @Override
    public List<DiscoveredCalendar> discoverCalendars(CalendarConnection connection) throws ProviderException {
        String url = connection.getCaldavBaseUrl();
        if (url == null || url.isBlank()) {
            throw new ProviderException("Connection " + connection.getId() + " has no ICS source URL configured");
        }
        // An ICS_SOURCE connection is one feed, one calendar - is_writable=0
        // always, so DeletionRuleService refuses any DELETE rule scoped to
        // it directly; filtering only ever happens via a published_feed.
        return List.of(new DiscoveredCalendar(url, connection.getDisplayName(), false));
    }

    @Override
    public List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar, SyncState syncState)
            throws ProviderException {
        String url = calendar.getRemoteCalendarId();
        FetchResult result = fetch(url, syncState == null ? null : syncState.getLastCtag());

        if (result.notModified()) {
            return lastKnownEventsByCalendarId.getOrDefault(calendar.getId(), List.of());
        }

        List<ProviderEvent> events = mapper.parseFeed(result.body(), calendar.getName());
        lastKnownEventsByCalendarId.put(calendar.getId(), events);
        if (syncState != null && result.etag() != null) {
            syncState.setLastCtag(result.etag());
        }
        return events;
    }

    @Override
    public void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event)
            throws ProviderException {
        throw new ProviderException("ICS_SOURCE is read-only - \"deletion\" only ever means exclusion "
                + "from a published_feed, never a write against the source");
    }

    @Override
    public ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                                      SnapshotFormat snapshotFormat, String snapshotPayload) throws ProviderException {
        throw new ProviderException("ICS_SOURCE is read-only and has nothing to restore to directly - "
                + "restoring a feed-side exclusion is handled via published_feed_override, not this method");
    }

    /**
     * The feed URL is whatever the user typed, so the response size is not
     * under this application's control - and the whole body is read into a
     * String before parsing. 32 MB is far beyond any real calendar feed while
     * still bounding what a hostile or broken endpoint can make the server
     * allocate.
     */
    private static final int MAX_FEED_BYTES = 32 * 1024 * 1024;

    private static String readCapped(java.io.InputStream body) throws java.io.IOException {
        byte[] bytes = body.readNBytes(MAX_FEED_BYTES + 1);
        if (bytes.length > MAX_FEED_BYTES) {
            throw new IllegalStateException("feed is larger than the " + (MAX_FEED_BYTES / (1024 * 1024))
                    + " MB limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record FetchResult(boolean notModified, String etag, String body) {
    }

    private FetchResult fetch(String url, String previousEtag) throws ProviderException {
        try {
            return restClient.get()
                    .uri(URI.create(url))
                    .headers(headers -> {
                        if (previousEtag != null) {
                            headers.set(HttpHeaders.IF_NONE_MATCH, previousEtag);
                        }
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
                        if (status == 304) {
                            return new FetchResult(true, etag, null);
                        }
                        if (status < 200 || status >= 300) {
                            // Without this the error page itself gets handed to
                            // the ICS parser, and the user sees a confusing
                            // "expected BEGIN" instead of "your URL 404s".
                            throw new IllegalStateException("HTTP " + status);
                        }
                        String body = readCapped(response.getBody());
                        return new FetchResult(false, etag, body);
                    }, true);
        } catch (RuntimeException e) {
            throw new ProviderException("Failed to fetch ICS feed " + url + ": " + e.getMessage(), e);
        }
    }
}
