package com.sykessec.calendarsync.provider;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;

import java.util.List;

/**
 * One implementation per provider (Google, MS Graph, CalDAV covering both
 * iCloud and generic, and the read-only ICS source). Write-capable
 * implementations expose both deleteEvent and createEvent from the start -
 * createEvent exists specifically to support TrashService's restore path,
 * not for general event authoring.
 *
 * Callers (TrashService) are responsible for the snapshot-before-delete
 * ordering; deleteEvent itself assumes the caller already has a durable
 * snapshot of the event being passed in.
 */
public interface CalendarProvider {

    /**
     * Whether this implementation handles the given provider type. CalDAV
     * is a single implementation shared by ICLOUD and CALDAV (iCloud is
     * just CalDAV with fixed host discovery and app-password auth), so a
     * provider can support more than one type - callers must not assume a
     * 1:1 type-to-bean mapping.
     */
    boolean supports(ProviderType type);

    /**
     * Full or incremental sync depending on what syncState carries
     * (syncToken/ctag) - implementations update syncState's tokens as a
     * side effect so the next call can go incremental.
     */
    List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar, SyncState syncState)
            throws ProviderException;

    /** Permanently deletes the event on the provider. Never called for a read-only ICS source. */
    void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event)
            throws ProviderException;

    /** Recreates an event from a stored snapshot; returns the new provider-side event, including its new uid. */
    ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                               SnapshotFormat snapshotFormat, String snapshotPayload)
            throws ProviderException;

    /**
     * Lists the calendar collections available on this connection, used by
     * the sync job to populate `calendar` rows the first time a connection
     * is synced. Read-only providers (ICS_SOURCE) don't have a notion of
     * multiple discoverable calendars, so the default just isn't overridden
     * by those implementations that don't need it.
     */
    default List<DiscoveredCalendar> discoverCalendars(CalendarConnection connection) throws ProviderException {
        throw new UnsupportedOperationException("Calendar discovery is not supported for this provider");
    }
}
