package com.sykessec.calendarsync.ics;

import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.ProviderEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsCalendarMapperTest {

    private final IcsCalendarMapper mapper = new IcsCalendarMapper();

    private static final String FEED = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:evt-1@example.com
            DTSTAMP:20260101T000000Z
            DTSTART:20260601T140000Z
            DTEND:20260601T150000Z
            SUMMARY:Weekly Sync
            END:VEVENT
            BEGIN:VEVENT
            UID:evt-2@example.com
            DTSTAMP:20260101T000000Z
            DTSTART:20260602T090000Z
            DTEND:20260602T093000Z
            SUMMARY:Standup
            END:VEVENT
            END:VCALENDAR
            """;

    @Test
    void parsesEveryVEventInAFeed() throws Exception {
        List<ProviderEvent> events = mapper.parseFeed(FEED, "Team Calendar");

        assertThat(events).hasSize(2);
        assertThat(events).extracting(ProviderEvent::uid)
                .containsExactlyInAnyOrder("evt-1@example.com", "evt-2@example.com");
        assertThat(events).extracting(ProviderEvent::title)
                .containsExactlyInAnyOrder("Weekly Sync", "Standup");
        assertThat(events).allMatch(e -> e.calendarName().equals("Team Calendar"));
        assertThat(events).allMatch(e -> e.rawFormat() == SnapshotFormat.ICS);
    }

    private static final String ALL_DAY_FEED = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:holiday@example.com
            DTSTAMP:20260101T000000Z
            DTSTART;VALUE=DATE:20260115
            DTEND;VALUE=DATE:20260116
            SUMMARY:Public holiday
            END:VEVENT
            END:VCALENDAR
            """;

    @Test
    void recognisesAnAllDayEventOnTheWayIn() throws Exception {
        List<ProviderEvent> events = mapper.parseFeed(ALL_DAY_FEED, "Holidays");

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.allDay()).isTrue();
            // Normalized to midnight UTC of the date the source named, which is
            // what makes toUtcDate() on the way out exact rather than lossy.
            assertThat(event.start()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
            assertThat(event.end()).isEqualTo(Instant.parse("2026-01-16T00:00:00Z"));
        });
    }

    @Test
    void timedEventsAreNotMistakenForAllDayOnes() throws Exception {
        assertThat(mapper.parseFeed(FEED, "Team Calendar")).allMatch(event -> !event.allDay());
    }

    /**
     * The regression this pair exists for. An all-day event carries a DATE, not
     * an instant; exporting it as DTSTART:20260115T000000Z moved it to the
     * previous evening for every subscriber west of UTC - the event landed on
     * the wrong DAY, which is not something a calendar may get wrong. Asserting
     * on the emitted property line rather than on a re-parsed model is
     * deliberate: the wire format is what the subscriber's client acts on.
     */
    @Test
    void anAllDayEventIsExportedAsADateNotAMidnightTimestamp() throws Exception {
        String feedText = build(mapper.parseFeed(ALL_DAY_FEED, "Holidays").getFirst(), ExportProfile.DEFAULT);

        assertThat(feedText).contains("DTSTART;VALUE=DATE:20260115");
        assertThat(feedText).contains("DTEND;VALUE=DATE:20260116");
        assertThat(feedText).doesNotContain("DTSTART:20260115T000000Z");
    }

    @Test
    void aTimedEventIsStillExportedAsAUtcTimestamp() throws Exception {
        String feedText = build(event(), ExportProfile.DEFAULT);

        assertThat(feedText).contains("DTSTART:20260901T100000Z");
        assertThat(feedText).doesNotContain("VALUE=DATE");
    }

    /**
     * An all-day event survives a full export/re-import cycle unchanged. A feed
     * this app publishes can itself be an ICS source for another instance, and
     * a round trip that quietly drifts by a day each hop is worse than one that
     * is wrong once.
     */
    @Test
    void allDaySurvivesARoundTripThroughTheFeed() throws Exception {
        ProviderEvent original = mapper.parseFeed(ALL_DAY_FEED, "Holidays").getFirst();
        String published = build(original, ExportProfile.DEFAULT);

        ProviderEvent reparsed = mapper.parseFeed(published, "Holidays").getFirst();

        assertThat(reparsed.allDay()).isTrue();
        assertThat(reparsed.start()).isEqualTo(original.start());
        assertThat(reparsed.end()).isEqualTo(original.end());
    }

    @Test
    void buildFeedProducesAParseableCalendarWithAllEvents() throws Exception {
        ProviderEvent event = new ProviderEvent("evt-x", "Quarterly Review", "desc", "Room 1",
                List.of("alice@example.com"), Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-01T11:00:00Z"), false, false, "Work", null, null);

        byte[] feedBytes = mapper.buildFeed(List.of(event), ExportProfile.DEFAULT);
        String feedText = new String(feedBytes, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(feedText).contains("BEGIN:VCALENDAR").contains("BEGIN:VEVENT").contains("UID:evt-x");
        assertThat(feedText).contains("SUMMARY:Quarterly Review");

        // Round trip: what buildFeed produced should itself parse back correctly.
        List<ProviderEvent> reparsed = mapper.parseFeed(feedText, "Work");
        assertThat(reparsed).hasSize(1);
        assertThat(reparsed.get(0).title()).isEqualTo("Quarterly Review");
        assertThat(reparsed.get(0).attendees()).containsExactly("mailto:alice@example.com");
    }

    @Test
    void defaultProfileEmitsNoExportPropertiesAtAll() throws Exception {
        // The behaviour every feed had before export settings existed, pinned
        // so an upgraded installation keeps serving what its subscribers
        // already have until someone deliberately changes a feed.
        String feedText = build(event(), ExportProfile.DEFAULT);

        assertThat(feedText).doesNotContain("CLASS:").doesNotContain("TRANSP:")
                .doesNotContain("X-MICROSOFT-CDO-BUSYSTATUS").doesNotContain("BEGIN:VALARM");
    }

    @Test
    void buildFeedGeneratesUidWhenEventHasNone() throws Exception {
        ProviderEvent event = new ProviderEvent(null, "No UID Event", null, null, List.of(),
                Instant.parse("2026-09-01T10:00:00Z"), null, false, false, "Work", null, null);

        byte[] feedBytes = mapper.buildFeed(List.of(event), ExportProfile.DEFAULT);
        String feedText = new String(feedBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(feedText).contains("UID:");
    }

    @Test
    void outlookOutOfOfficeProfileStampsAllThreePropertiesExactlyOnce() throws Exception {
        String feedText = build(event(), new ExportProfile(ExportTarget.OUTLOOK, EventClassification.PRIVATE,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.STRIP, 15));

        assertThat(feedText).contains("CLASS:PRIVATE");
        assertThat(feedText).contains("TRANSP:OPAQUE");
        assertThat(feedText).contains("X-MICROSOFT-CDO-BUSYSTATUS:OOF");
        assertThat(feedText).doesNotContain("BEGIN:VALARM");

        // MS-OXCICAL allows exactly one instance per event, and Outlook's
        // behaviour on a second is undefined.
        assertThat(occurrences(feedText, "CLASS:")).isEqualTo(1);
        assertThat(occurrences(feedText, "TRANSP:")).isEqualTo(1);
        assertThat(occurrences(feedText, "X-MICROSOFT-CDO-BUSYSTATUS")).isEqualTo(1);
    }

    @Test
    void googleAndProtonTargetsOmitTheMicrosoftExtensionButKeepTheStandardProperties() throws Exception {
        for (ExportTarget target : List.of(ExportTarget.GOOGLE, ExportTarget.PROTON)) {
            String feedText = build(event(), new ExportProfile(target, EventClassification.PRIVATE,
                    EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.STRIP, 15));

            assertThat(feedText).contains("CLASS:PRIVATE");
            // Out of office has no standard spelling, so it degrades to busy
            // rather than being dropped - the time still has to be blocked.
            assertThat(feedText).contains("TRANSP:OPAQUE");
            assertThat(feedText).doesNotContain("X-MICROSOFT-CDO-BUSYSTATUS");
        }
    }

    @Test
    void freeBusyStatusIsWrittenAsTransparent() throws Exception {
        String feedText = build(event(), new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                EventBusyStatus.FREE, AlarmPolicy.STRIP, 15));

        assertThat(feedText).contains("TRANSP:TRANSPARENT").contains("X-MICROSOFT-CDO-BUSYSTATUS:FREE");
        assertThat(feedText).doesNotContain("CLASS:");
    }

    @Test
    void fixedAlarmPolicyWritesOneDisplayReminderBeforeTheStart() throws Exception {
        String feedText = build(event(), new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                EventBusyStatus.UNCHANGED, AlarmPolicy.FIXED, 30));

        assertThat(occurrences(feedText, "BEGIN:VALARM")).isEqualTo(1);
        assertThat(feedText).contains("ACTION:DISPLAY");
        // Negative: TRIGGER is relative to DTSTART, so before means -PT30M.
        assertThat(feedText).contains("TRIGGER:-PT30M");
        assertThat(feedText).contains("DESCRIPTION:Quarterly Review");
    }

    @Test
    void passthroughCopiesTheSourceEventsOwnAlarm() throws Exception {
        String feedText = build(eventWithIcsSnapshot(SOURCE_WITH_ALARM),
                new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                        EventBusyStatus.UNCHANGED, AlarmPolicy.PASSTHROUGH, 15));

        assertThat(occurrences(feedText, "BEGIN:VALARM")).isEqualTo(1);
        assertThat(feedText).contains("TRIGGER:-PT10M").contains("DESCRIPTION:Source reminder");
    }

    @Test
    void stripDropsAnAlarmTheSourceEventHad() throws Exception {
        String feedText = build(eventWithIcsSnapshot(SOURCE_WITH_ALARM), ExportProfile.DEFAULT);

        assertThat(feedText).doesNotContain("BEGIN:VALARM").doesNotContain("TRIGGER");
    }

    @Test
    void passthroughEmitsNoAlarmWhenTheSnapshotIsNotIcs() throws Exception {
        // Google and Microsoft Graph snapshots are JSON: there is no VALARM to
        // copy, and the export says so by producing none rather than failing.
        ProviderEvent jsonSourced = new ProviderEvent("evt-json", "Quarterly Review", null, null, List.of(),
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"), false, false,
                "Work", SnapshotFormat.GOOGLE_JSON, "{\"id\":\"evt-json\"}");

        String feedText = build(jsonSourced, new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                EventBusyStatus.UNCHANGED, AlarmPolicy.PASSTHROUGH, 15));

        assertThat(feedText).contains("UID:evt-json").doesNotContain("BEGIN:VALARM");
    }

    @Test
    void passthroughSurvivesAnUnparseableSnapshot() throws Exception {
        // One bad snapshot must cost the subscriber a reminder, not the feed.
        ProviderEvent broken = new ProviderEvent("evt-broken", "Quarterly Review", null, null, List.of(),
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"), false, false,
                "Work", SnapshotFormat.ICS, "this is not an ICS document");

        String feedText = build(broken, new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                EventBusyStatus.UNCHANGED, AlarmPolicy.PASSTHROUGH, 15));

        assertThat(feedText).contains("UID:evt-broken").doesNotContain("BEGIN:VALARM");
    }

    @Test
    void anExportedEventStillRoundTripsThroughTheParser() throws Exception {
        String feedText = build(event(), new ExportProfile(ExportTarget.OUTLOOK, EventClassification.PRIVATE,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.FIXED, 15));

        List<ProviderEvent> reparsed = mapper.parseFeed(feedText, "Work");
        assertThat(reparsed).hasSize(1);
        assertThat(reparsed.get(0).title()).isEqualTo("Quarterly Review");
    }

    @Test
    void profileRejectsAReminderOffsetOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                EventBusyStatus.UNCHANGED, AlarmPolicy.FIXED, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExportProfile(ExportTarget.UNIVERSAL, EventClassification.UNCHANGED,
                EventBusyStatus.UNCHANGED, AlarmPolicy.FIXED, ExportProfile.MAX_ALARM_MINUTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileReadsMissingSettingsAsLeaveItAlone() {
        ExportProfile profile = new ExportProfile(null, null, null, null, 15);

        assertThat(profile.target()).isEqualTo(ExportTarget.UNIVERSAL);
        assertThat(profile.classification()).isEqualTo(EventClassification.UNCHANGED);
        assertThat(profile.busyStatus()).isEqualTo(EventBusyStatus.UNCHANGED);
        assertThat(profile.alarmPolicy()).isEqualTo(AlarmPolicy.STRIP);
        assertThat(profile.exportedPropertyLines()).containsExactly("VALARM: none");
    }

    @Test
    void profileFlagsAnOutOfOfficeSettingThatTheTargetCannotExpress() {
        ExportProfile google = new ExportProfile(ExportTarget.GOOGLE, EventClassification.UNCHANGED,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.STRIP, 15);
        ExportProfile outlook = new ExportProfile(ExportTarget.OUTLOOK, EventClassification.UNCHANGED,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.STRIP, 15);

        assertThat(google.busyStatusIsDowngraded()).isTrue();
        assertThat(google.emitsMicrosoftBusyStatus()).isFalse();
        assertThat(outlook.busyStatusIsDowngraded()).isFalse();
        assertThat(outlook.emitsMicrosoftBusyStatus()).isTrue();
    }

    private static final String SOURCE_WITH_ALARM = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:evt-alarm@example.com
            DTSTAMP:20260101T000000Z
            DTSTART:20260901T100000Z
            DTEND:20260901T110000Z
            SUMMARY:Quarterly Review
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            DESCRIPTION:Source reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

    private ProviderEvent event() {
        return new ProviderEvent("evt-x", "Quarterly Review", null, null, List.of(),
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"), false, false,
                "Work", null, null);
    }

    private ProviderEvent eventWithIcsSnapshot(String snapshot) {
        return new ProviderEvent("evt-alarm@example.com", "Quarterly Review", null, null, List.of(),
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"), false, false,
                "Work", SnapshotFormat.ICS, snapshot);
    }

    private String build(ProviderEvent event, ExportProfile profile) throws Exception {
        return new String(mapper.buildFeed(List.of(event), profile), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
