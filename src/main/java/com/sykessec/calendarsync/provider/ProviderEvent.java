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
 */
public record ProviderEvent(
        String uid,
        String title,
        String description,
        String location,
        List<String> attendees,
        Instant start,
        Instant end,
        boolean recurring,
        String calendarName,
        SnapshotFormat rawFormat,
        String rawPayload
) {
    public boolean hasSnapshot() {
        return rawFormat != null && rawPayload != null && !rawPayload.isBlank();
    }
}
