package com.sykessec.calendarsync.provider.msgraph;

import com.microsoft.graph.models.Attendee;
import com.microsoft.graph.models.BodyType;
import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.EmailAddress;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.ItemBody;
import com.microsoft.graph.models.Location;
import com.microsoft.graph.models.PatternedRecurrence;
import com.sykessec.calendarsync.provider.ProviderEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** No network required - MsGraphEventMapper only touches in-memory model objects and Jackson. */
class MsGraphEventMapperTest {

    private final MsGraphEventMapper mapper = new MsGraphEventMapper();

    private Event sampleEvent() {
        Event event = new Event();
        event.setId("graph-event-id-123");
        event.setSubject("Budget Review");

        ItemBody body = new ItemBody();
        body.setContent("Bring last quarter's numbers");
        body.setContentType(BodyType.Text);
        event.setBody(body);

        Location location = new Location();
        location.setDisplayName("Conference Room A");
        event.setLocation(location);

        DateTimeTimeZone start = new DateTimeTimeZone();
        start.setDateTime("2026-08-01T09:00:00.0000000");
        start.setTimeZone("UTC");
        event.setStart(start);

        DateTimeTimeZone end = new DateTimeTimeZone();
        end.setDateTime("2026-08-01T10:00:00.0000000");
        end.setTimeZone("UTC");
        event.setEnd(end);

        Attendee alice = new Attendee();
        EmailAddress aliceEmail = new EmailAddress();
        aliceEmail.setAddress("alice@example.com");
        alice.setEmailAddress(aliceEmail);
        event.setAttendees(List.of(alice));

        return event;
    }

    @Test
    void mapsCoreFields() throws Exception {
        ProviderEvent result = mapper.toProviderEvent(sampleEvent(), "Work");

        assertThat(result.uid()).isEqualTo("graph-event-id-123");
        assertThat(result.title()).isEqualTo("Budget Review");
        assertThat(result.description()).isEqualTo("Bring last quarter's numbers");
        assertThat(result.location()).isEqualTo("Conference Room A");
        assertThat(result.attendees()).containsExactly("alice@example.com");
        assertThat(result.start()).isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
        assertThat(result.end()).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
        assertThat(result.recurring()).isFalse();
    }

    @Test
    void recurringDetectedFromRecurrenceField() throws Exception {
        Event event = sampleEvent();
        event.setRecurrence(new PatternedRecurrence());
        assertThat(mapper.toProviderEvent(event, "Work").recurring()).isTrue();
    }

    @Test
    void snapshotExcludesProviderGeneratedIdAndRoundTripsSubject() throws Exception {
        ProviderEvent mapped = mapper.toProviderEvent(sampleEvent(), "Work");

        assertThat(mapped.rawPayload()).doesNotContain("graph-event-id-123");
        assertThat(mapped.rawPayload()).contains("Budget Review");

        Event restored = mapper.fromSnapshot(mapped.rawPayload());
        assertThat(restored.getId()).isNull();
        assertThat(restored.getSubject()).isEqualTo("Budget Review");
        assertThat(restored.getLocation().getDisplayName()).isEqualTo("Conference Room A");
        assertThat(restored.getAttendees()).hasSize(1);
        assertThat(restored.getAttendees().get(0).getEmailAddress().getAddress()).isEqualTo("alice@example.com");
    }
}
