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
import java.util.TimeZone;

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

    /**
     * Google signals all-day by populating date instead of dateTime - the two
     * are mutually exclusive - and the resulting instant has to be midnight UTC
     * of that date, because IcsCalendarMapper reads the date straight back out
     * in UTC to write VALUE=DATE.
     *
     * The JVM zone is shifted here to pin that as an invariant rather than an
     * accident of the machine the suite runs on. It holds today - a date-only
     * google-http-client DateTime parses with a zone shift of 0 whatever the
     * default zone is, which was verified rather than assumed - so this is not
     * a regression test for a bug that existed, it is a guard on a property the
     * exporter now depends on and a library upgrade could quietly change.
     */
    @Test
    void marksAnAllDayEventAndPinsItToMidnightUtcRegardlessOfTheJvmZone() throws Exception {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));

            Event event = sampleEvent();
            event.setStart(new EventDateTime().setDate(new DateTime("2026-07-01")));
            event.setEnd(new EventDateTime().setDate(new DateTime("2026-07-02")));

            ProviderEvent mapped = mapper.toProviderEvent(event, "Work");

            assertThat(mapped.allDay()).isTrue();
            assertThat(mapped.start()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
            assertThat(mapped.end()).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void aTimedEventIsNotMarkedAllDay() throws Exception {
        assertThat(mapper.toProviderEvent(sampleEvent(), "Work").allDay()).isFalse();
    }
}
