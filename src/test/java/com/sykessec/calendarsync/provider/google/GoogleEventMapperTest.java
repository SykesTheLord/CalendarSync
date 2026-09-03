package com.sykessec.calendarsync.provider.google;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.provider.ProviderEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No network required - GoogleEventMapper only touches an in-memory Event
 * model + the JSON factory, both offline-testable. GoogleOAuthService's
 * constructor only builds a transport object (no connection attempted), and
 * jsonFactory() doesn't need OAuth credentials configured, so a plain
 * instance is fine here despite no client-id/secret being set.
 */
class GoogleEventMapperTest {

    private final GoogleEventMapper mapper;

    GoogleEventMapperTest() throws Exception {
        this.mapper = new GoogleEventMapper(new GoogleOAuthService(new AppProperties()));
    }

    private Event sampleEvent() {
        Event event = new Event();
        event.setId("google-event-id-123");
        event.setEtag("\"etag-value\"");
        event.setHtmlLink("https://calendar.google.com/event?eid=abc");
        event.setSummary("Design Review");
        event.setDescription("Bring mockups");
        event.setLocation("Zoom");
        event.setICalUID("ical-uid-value@google.com");
        event.setStart(new EventDateTime().setDateTime(new DateTime("2026-07-01T09:00:00Z")));
        event.setEnd(new EventDateTime().setDateTime(new DateTime("2026-07-01T10:00:00Z")));
        // ArrayList, not List.of(...): real Google API responses populate
        // list fields with mutable ArrayLists via JSON deserialization, and
        // Event.clone()'s deep-copy reflectively re-instantiates whatever
        // concrete List type it finds - Java's immutable List.of() has no
        // public no-arg constructor and breaks that clone.
        event.setAttendees(new ArrayList<>(List.of(new EventAttendee().setEmail("alice@example.com"),
                new EventAttendee().setEmail("bob@example.com"))));
        return event;
    }

    @Test
    void mapsFieldsAndStripsProviderGeneratedOnesFromSnapshot() throws Exception {
        ProviderEvent result = mapper.toProviderEvent(sampleEvent(), "Work");

        assertThat(result.uid()).isEqualTo("google-event-id-123");
        assertThat(result.title()).isEqualTo("Design Review");
        assertThat(result.description()).isEqualTo("Bring mockups");
        assertThat(result.location()).isEqualTo("Zoom");
        assertThat(result.attendees()).containsExactly("alice@example.com", "bob@example.com");
        assertThat(result.start()).isEqualTo(Instant.parse("2026-07-01T09:00:00Z"));
        assertThat(result.end()).isEqualTo(Instant.parse("2026-07-01T10:00:00Z"));
        assertThat(result.calendarName()).isEqualTo("Work");

        // The snapshot must have the provider-generated fields stripped -
        // restoring from it must not carry a stale id/etag/htmlLink forward.
        assertThat(result.rawPayload()).doesNotContain("google-event-id-123");
        assertThat(result.rawPayload()).doesNotContain("etag-value");
        assertThat(result.rawPayload()).doesNotContain("calendar.google.com");
        assertThat(result.rawPayload()).doesNotContain("ical-uid-value");
        assertThat(result.rawPayload()).contains("Design Review");
    }

    @Test
    void recurringDetectedFromRecurrenceField() throws Exception {
        Event event = sampleEvent();
        event.setRecurrence(new ArrayList<>(List.of("RRULE:FREQ=WEEKLY;COUNT=4")));
        assertThat(mapper.toProviderEvent(event, "Work").recurring()).isTrue();
        assertThat(mapper.toProviderEvent(sampleEvent(), "Work").recurring()).isFalse();
    }

    @Test
    void fromSnapshotRoundTripsBackToAnEventBody() throws Exception {
        ProviderEvent mapped = mapper.toProviderEvent(sampleEvent(), "Work");
        Event restored = mapper.fromSnapshot(mapped.rawPayload());

        assertThat(restored.getSummary()).isEqualTo("Design Review");
        assertThat(restored.getId()).isNull();
        assertThat(restored.getEtag()).isNull();
    }
}
