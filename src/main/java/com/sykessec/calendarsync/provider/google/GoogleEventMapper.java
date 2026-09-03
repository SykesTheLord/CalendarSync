package com.sykessec.calendarsync.provider.google;

import com.google.api.client.json.JsonFactory;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
public class GoogleEventMapper {

    private final JsonFactory jsonFactory;

    public GoogleEventMapper(GoogleOAuthService oAuthService) {
        this.jsonFactory = oAuthService.jsonFactory();
    }

    /**
     * Cleans provider-generated fields before the event is used as a
     * deletion_audit snapshot - id/etag/htmlLink/created/updated/iCalUID
     * are all Google-assigned and meaningless (or actively wrong) on
     * restore, per the spec's snapshot-cleaning rule for Google events.
     */
    public ProviderEvent toProviderEvent(Event event, String calendarName) throws ProviderException {
        Event clean = event.clone();
        clean.setId(null);
        clean.setEtag(null);
        clean.setHtmlLink(null);
        clean.setCreated(null);
        clean.setUpdated(null);
        clean.setICalUID(null);

        List<String> attendees = event.getAttendees() == null ? List.of()
                : event.getAttendees().stream().map(EventAttendee::getEmail).toList();
        boolean recurring = event.getRecurrence() != null && !event.getRecurrence().isEmpty();

        try {
            String snapshot = jsonFactory.toString(clean);
            return new ProviderEvent(
                    event.getId(),
                    event.getSummary(),
                    event.getDescription(),
                    event.getLocation(),
                    attendees,
                    toInstant(event.getStart()),
                    toInstant(event.getEnd()),
                    recurring,
                    calendarName,
                    SnapshotFormat.GOOGLE_JSON,
                    snapshot);
        } catch (IOException e) {
            throw new ProviderException("Failed to serialize Google event snapshot: " + e.getMessage(), e);
        }
    }

    /** Deserializes a stored snapshot back into an Event body for events.insert(). */
    public Event fromSnapshot(String snapshotJson) throws ProviderException {
        try {
            return jsonFactory.fromString(snapshotJson, Event.class);
        } catch (IOException e) {
            throw new ProviderException("Failed to parse stored Google event snapshot: " + e.getMessage(), e);
        }
    }

    private Instant toInstant(EventDateTime eventDateTime) {
        if (eventDateTime == null) {
            return null;
        }
        if (eventDateTime.getDateTime() != null) {
            return Instant.ofEpochMilli(eventDateTime.getDateTime().getValue());
        }
        if (eventDateTime.getDate() != null) {
            return Instant.ofEpochMilli(eventDateTime.getDate().getValue());
        }
        return null;
    }
}
