package com.sykessec.calendarsync.provider.msgraph;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.DiscoveredCalendar;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.service.CredentialCipher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * No delta-query incremental sync in this build stage - MS Graph's delta
 * API (calendarView/delta, @odata.deltaLink) is a materially larger surface
 * than Google's single syncToken field, and a full events().get() poll per
 * cycle is still correct, just less efficient. Recorded as a deviation in
 * NOTES.md; delta support is a reasonable later improvement, not required
 * for this to function.
 */
@Service
public class MicrosoftGraphProvider implements CalendarProvider {

    private final MsGraphOAuthService oAuthService;
    private final MsGraphEventMapper mapper;
    private final CalendarConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;

    public MicrosoftGraphProvider(MsGraphOAuthService oAuthService, MsGraphEventMapper mapper,
                                   CalendarConnectionRepository connectionRepository, CredentialCipher credentialCipher) {
        this.oAuthService = oAuthService;
        this.mapper = mapper;
        this.connectionRepository = connectionRepository;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public boolean supports(ProviderType type) {
        return type == ProviderType.MS_GRAPH;
    }

    /**
     * Graph paginates every collection and defaults to a small page (10 for
     * /events), handing back an @odata.nextLink for the rest. Both collection
     * calls here follow that link to exhaustion - reading only the first page
     * silently hid all but the first handful of events from the rule engine
     * and from every published feed built on this connection. MAX_PAGES is a
     * safety stop so a server that keeps handing back a nextLink can't spin
     * the sync job forever.
     */
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    @Override
    public List<DiscoveredCalendar> discoverCalendars(CalendarConnection connection) throws ProviderException {
        GraphServiceClient client = clientFor(connection);
        List<com.microsoft.graph.models.Calendar> items = new ArrayList<>();
        try {
            var response = client.me().calendars().get(config -> {
                config.queryParameters.top = PAGE_SIZE;
            });
            for (int page = 0; response != null && page < MAX_PAGES; page++) {
                if (response.getValue() != null) {
                    items.addAll(response.getValue());
                }
                String next = response.getOdataNextLink();
                if (next == null || next.isBlank()) {
                    break;
                }
                response = client.me().calendars().withUrl(next).get();
            }
        } catch (RuntimeException e) {
            throw wrapGraphFailure("MS Graph calendars.get", e);
        }
        return items.stream()
                .map(c -> new DiscoveredCalendar(c.getId(), c.getName(), Boolean.TRUE.equals(c.getCanEdit())))
                .toList();
    }

    @Override
    public List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar, SyncState syncState)
            throws ProviderException {
        GraphServiceClient client = clientFor(connection);
        var eventsBuilder = client.me().calendars().byCalendarId(calendar.getRemoteCalendarId()).events();

        List<Event> items = new ArrayList<>();
        try {
            var response = eventsBuilder.get(config -> {
                config.queryParameters.top = PAGE_SIZE;
            });
            for (int page = 0; response != null && page < MAX_PAGES; page++) {
                if (response.getValue() != null) {
                    items.addAll(response.getValue());
                }
                String next = response.getOdataNextLink();
                if (next == null || next.isBlank()) {
                    break;
                }
                response = eventsBuilder.withUrl(next).get();
            }
        } catch (RuntimeException e) {
            throw wrapGraphFailure("MS Graph events.get for calendar " + calendar.getId(), e);
        }

        List<ProviderEvent> events = new ArrayList<>();
        for (Event item : items) {
            events.add(mapper.toProviderEvent(item, calendar.getName()));
        }
        return events;
    }

    @Override
    public void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event)
            throws ProviderException {
        if (!calendar.isWritable()) {
            throw new ProviderException("Refusing to delete on a read-only MS Graph calendar (" + calendar.getName() + ")");
        }
        GraphServiceClient client = clientFor(connection);
        try {
            client.me().calendars().byCalendarId(calendar.getRemoteCalendarId())
                    .events().byEventId(event.uid()).delete();
        } catch (RuntimeException e) {
            throw wrapGraphFailure("MS Graph events.delete for event " + event.uid(), e);
        }
    }

    @Override
    public ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                                      SnapshotFormat snapshotFormat, String snapshotPayload) throws ProviderException {
        if (snapshotFormat != SnapshotFormat.MS_GRAPH_JSON) {
            throw new ProviderException("MicrosoftGraphProvider can only restore MS_GRAPH_JSON snapshots, got " + snapshotFormat);
        }
        GraphServiceClient client = clientFor(connection);
        Event body = mapper.fromSnapshot(snapshotPayload);

        try {
            Event created = client.me().calendars().byCalendarId(calendar.getRemoteCalendarId()).events().post(body);
            return mapper.toProviderEvent(created, calendar.getName());
        } catch (RuntimeException e) {
            throw wrapGraphFailure("MS Graph events.post to restore an event", e);
        }
    }

    /**
     * The Kiota-generated SDK throws unchecked exceptions (ApiException and
     * friends) rather than a checked IOException, so every call site must
     * catch and convert explicitly - an uncaught one would propagate past
     * ConnectionSyncJob's `catch (ProviderException)`, skipping its
     * sync_state.last_error bookkeeping entirely.
     */
    private ProviderException wrapGraphFailure(String what, RuntimeException e) {
        return new ProviderException(what + " failed: " + e.getMessage(), e);
    }

    private GraphServiceClient clientFor(CalendarConnection connection) throws ProviderException {
        String serializedCache = tokenCacheFor(connection);
        MsGraphOAuthService.TokenResult tokenResult = oAuthService.accessTokenFor(serializedCache);

        if (!tokenResult.updatedTokenCache().equals(serializedCache)) {
            connection.setEncryptedCredentials(credentialCipher.encode(tokenResult.updatedTokenCache()));
            connectionRepository.save(connection);
        }

        return new GraphServiceClient(new StaticTokenCredential(tokenResult), "https://graph.microsoft.com/.default");
    }

    /** calendar_connection.encrypted_credentials holds MSAL's serialized token cache, decoded via CredentialCipher. */
    private String tokenCacheFor(CalendarConnection connection) throws ProviderException {
        byte[] raw = connection.getEncryptedCredentials();
        if (raw == null || raw.length == 0) {
            throw new ProviderException("Connection " + connection.getId() + " has no Microsoft token cache stored");
        }
        return credentialCipher.decode(raw);
    }

    /** Wraps an already-acquired MSAL token so azure-identity's GraphServiceClient constructor can use it. */
    private record StaticTokenCredential(MsGraphOAuthService.TokenResult tokenResult) implements TokenCredential {
        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            Instant expiresAt = tokenResult.expiresOn() == null
                    ? Instant.now().plusSeconds(300)
                    : tokenResult.expiresOn().toInstant();
            return Mono.just(new AccessToken(tokenResult.accessToken(), expiresAt.atOffset(ZoneOffset.UTC)));
        }
    }
}
