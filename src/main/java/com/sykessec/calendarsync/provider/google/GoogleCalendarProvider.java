package com.sykessec.calendarsync.provider.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.DiscoveredCalendar;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.service.CredentialCipher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Skips write attempts on is_writable=0 calendars (subscribed/holiday
 * calendars) entirely - callers (the sync job, DeletionRuleService) are
 * responsible for never routing a delete at one; this class only implements
 * the provider mechanics.
 */
@Service
public class GoogleCalendarProvider implements CalendarProvider {

    private static final String APPLICATION_NAME = "CalendarSync";

    private final GoogleOAuthService oAuthService;
    private final GoogleEventMapper mapper;
    private final CredentialCipher credentialCipher;

    public GoogleCalendarProvider(GoogleOAuthService oAuthService, GoogleEventMapper mapper,
                                   CredentialCipher credentialCipher) {
        this.oAuthService = oAuthService;
        this.mapper = mapper;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public boolean supports(ProviderType type) {
        return type == ProviderType.GOOGLE;
    }

    @Override
    public List<DiscoveredCalendar> discoverCalendars(CalendarConnection connection) throws ProviderException {
        Calendar client = clientFor(connection);
        try {
            var items = client.calendarList().list().execute().getItems();
            if (items == null) {
                return List.of();
            }
            return items.stream()
                    .map(entry -> new DiscoveredCalendar(entry.getId(), entry.getSummary(),
                            "owner".equals(entry.getAccessRole()) || "writer".equals(entry.getAccessRole())))
                    .toList();
        } catch (IOException e) {
            throw new ProviderException("Google calendarList.list failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar, SyncState syncState)
            throws ProviderException {
        Calendar client = clientFor(connection);
        String calendarId = calendar.getRemoteCalendarId();
        String syncToken = syncState == null ? null : syncState.getLastSyncToken();

        List<Event> items = new ArrayList<>();
        String pageToken = null;
        String nextSyncToken = null;
        boolean tokenExpired = false;

        try {
            do {
                Calendar.Events.List request = client.events().list(calendarId)
                        .setSingleEvents(true)
                        .setShowDeleted(syncToken != null)
                        .setPageToken(pageToken);
                if (syncToken != null) {
                    request.setSyncToken(syncToken);
                }
                Events page = request.execute();
                if (page.getItems() != null) {
                    items.addAll(page.getItems());
                }
                pageToken = page.getNextPageToken();
                if (page.getNextSyncToken() != null) {
                    nextSyncToken = page.getNextSyncToken();
                }
            } while (pageToken != null);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 410) {
                // Sync token expired/invalid - the caller must fall back to
                // a full resync (clear syncState.lastSyncToken and retry).
                tokenExpired = true;
            } else {
                throw new ProviderException("Google events.list failed: " + e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new ProviderException("Google events.list failed: " + e.getMessage(), e);
        }

        if (tokenExpired) {
            throw new GoogleSyncTokenExpiredException();
        }

        if (syncState != null) {
            syncState.setLastSyncToken(nextSyncToken);
        }

        List<ProviderEvent> events = new ArrayList<>();
        for (Event item : items) {
            // Cancelled events are already-gone - no action needed, per spec.
            if ("cancelled".equals(item.getStatus())) {
                continue;
            }
            events.add(mapper.toProviderEvent(item, calendar.getName()));
        }
        return events;
    }

    @Override
    public void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event)
            throws ProviderException {
        if (!calendar.isWritable()) {
            throw new ProviderException("Refusing to delete on a read-only Google calendar (" + calendar.getName() + ")");
        }
        Calendar client = clientFor(connection);
        try {
            client.events().delete(calendar.getRemoteCalendarId(), event.uid()).execute();
        } catch (IOException e) {
            throw new ProviderException("Google events.delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                                      SnapshotFormat snapshotFormat, String snapshotPayload) throws ProviderException {
        if (snapshotFormat != SnapshotFormat.GOOGLE_JSON) {
            throw new ProviderException("GoogleCalendarProvider can only restore GOOGLE_JSON snapshots, got " + snapshotFormat);
        }
        Calendar client = clientFor(connection);
        Event body = mapper.fromSnapshot(snapshotPayload);

        try {
            Event created = client.events().insert(calendar.getRemoteCalendarId(), body).execute();
            return mapper.toProviderEvent(created, calendar.getName());
        } catch (IOException e) {
            throw new ProviderException("Google events.insert failed: " + e.getMessage(), e);
        }
    }

    private Calendar clientFor(CalendarConnection connection) throws ProviderException {
        String refreshToken = refreshTokenFor(connection);
        GoogleCredential credential = oAuthService.credentialFor(refreshToken);
        return new Calendar.Builder(oAuthService.httpTransport(), oAuthService.jsonFactory(), credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /** calendar_connection.encrypted_credentials holds the raw refresh token, decoded via CredentialCipher. */
    private String refreshTokenFor(CalendarConnection connection) throws ProviderException {
        byte[] raw = connection.getEncryptedCredentials();
        if (raw == null || raw.length == 0) {
            throw new ProviderException("Connection " + connection.getId() + " has no Google refresh token stored");
        }
        return credentialCipher.decode(raw);
    }

    /** Signals the caller (sync job) that it must clear syncState and retry with a full sync. */
    public static class GoogleSyncTokenExpiredException extends ProviderException {
        public GoogleSyncTokenExpiredException() {
            super("Google sync token expired or invalid - a full resync is required");
        }
    }
}
