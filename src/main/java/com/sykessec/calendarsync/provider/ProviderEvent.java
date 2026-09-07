package com.sykessec.calendarsync.provider;

import com.sykessec.calendarsync.entity.enums.SnapshotFormat;

import java.time.Instant;
import java.util.List;

/**
 * Normalized view of a calendar event, produced by every CalendarProvider
 * implementation so the RuleEngine and TrashService have exactly one shape
 * to work with regardless of source (Google JSON, MS Graph JSON, ICS text).
 *
 * rawFormat/rawPayload carry enough to fully reconstruct the event - this is
 * what TrashService persists verbatim as deletion_audit.event_snapshot, so a
 * provider MUST populate them before a delete is attempted.
 *
 * allDay is carried alongside start/end rather than being inferred from them,
 * and it is not cosmetic. An all-day event is a DATE, not an instant: every
 * provider reports one as midnight in some zone, so normalizing to an Instant
 * and stopping there loses the distinction, and IcsCalendarMapper then wrote
 * DTSTART:20260115T000000Z for what the source called
 * DTSTART;VALUE=DATE:20260115. A subscriber west of UTC sees that as a
 * 24-hour block starting the previous evening - the event lands on the wrong
 * DAY, which is the one thing a calendar has to get right. start/end stay
 * Instants (the rule engine's DURATION and START conditions are defined on
 * them); allDay says how they must be WRITTEN, and every producer has to
 * answer it explicitly because the compiler asks.
 */
public record ProviderEvent(
        String uid,
        String title,
        String description,
        String location,
        List<String> attendees,
        Instant start,
        Instant end,
        boolean allDay,
        boolean recurring,
        String calendarName,
        SnapshotFormat rawFormat,
        String rawPayload
) {
    public boolean hasSnapshot() {
        return rawFormat != null && rawPayload != null && !rawPayload.isBlank();
    }
}
