package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.provider.ProviderEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalDavEventMapperTest {

    private final CalDavEventMapper mapper = new CalDavEventMapper();

    private static final String ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:evt-123@example.com
            DTSTAMP:20260101T000000Z
            DTSTART:20260601T140000Z
            DTEND:20260601T150000Z
            SUMMARY:Quarterly Planning
            DESCRIPTION:Bring the roadmap
            LOCATION:Room 4B
            ATTENDEE:mailto:alice@example.com
            ATTENDEE:mailto:bob@example.com
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            END:VCALENDAR
            """;

    @Test
    void parsesCoreFieldsFromIcs() throws Exception {
        ProviderEvent event = mapper.toProviderEvent(ICS, "Work");

        assertThat(event.uid()).isEqualTo("evt-123@example.com");
        assertThat(event.title()).isEqualTo("Quarterly Planning");
        assertThat(event.description()).isEqualTo("Bring the roadmap");
        assertThat(event.location()).isEqualTo("Room 4B");
        assertThat(event.attendees()).containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
        assertThat(event.recurring()).isTrue();
        assertThat(event.calendarName()).isEqualTo("Work");
        assertThat(event.start().toString()).contains("2026-06-01T14:00:00Z");
        assertThat(event.end().toString()).contains("2026-06-01T15:00:00Z");
        assertThat(event.rawPayload()).isEqualTo(ICS);
    }

    @Test
    void nonRecurringEventHasNoRRule() throws Exception {
        String singleOccurrence = ICS.replace("RRULE:FREQ=WEEKLY;COUNT=4\n", "");
        ProviderEvent event = mapper.toProviderEvent(singleOccurrence, "Work");
        assertThat(event.recurring()).isFalse();
    }

    @Test
    void withFreshUidReplacesUidAndKeepsOtherFields() throws Exception {
        String rewritten = mapper.withFreshUid(ICS, "brand-new-uid@calendarsync");

        assertThat(rewritten).contains("UID:brand-new-uid@calendarsync");
        assertThat(rewritten).doesNotContain("evt-123@example.com");
        assertThat(rewritten).contains("SUMMARY:Quarterly Planning");

        ProviderEvent reparsed = mapper.toProviderEvent(rewritten, "Work");
        assertThat(reparsed.uid()).isEqualTo("brand-new-uid@calendarsync");
        assertThat(reparsed.title()).isEqualTo("Quarterly Planning");
    }
}
