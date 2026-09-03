package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.enums.ProviderType;

/** Flattened view of a synced calendar for the Calendars UI - joins CalendarEntity to its parent connection. */
public record CalendarSummary(Long calendarId, String calendarName, boolean writable,
                               ProviderType provider, String connectionDisplayName, Long connectionId) {
}
