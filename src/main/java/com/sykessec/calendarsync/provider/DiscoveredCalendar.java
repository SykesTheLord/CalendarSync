package com.sykessec.calendarsync.provider;

/** A calendar collection found on the provider, before it's persisted as a `calendar` row. */
public record DiscoveredCalendar(String remoteCalendarId, String name, boolean writable) {
}
