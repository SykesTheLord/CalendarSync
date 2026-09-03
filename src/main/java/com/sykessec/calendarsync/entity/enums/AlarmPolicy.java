package com.sykessec.calendarsync.entity.enums;

/**
 * What happens to reminders ({@code VALARM}) in a published feed.
 *
 * STRIP is the default and the historical behaviour: the export builds each
 * VEVENT from normalized fields, so before this setting existed no feed could
 * ever carry an alarm. It stays the default because a feed is usually a *view*
 * of someone else's calendar - firing the owner's reminders on every
 * subscriber's phone is rarely what either of them wanted.
 *
 * The important limit on all three options: this controls only what the ICS
 * asks for. It cannot override reminders the receiving side imposes on its own
 * - Outlook's per-calendar defaults, an Exchange policy, or Google's
 * notification settings for a subscribed calendar, which are configured by the
 * subscriber and ignore the feed entirely.
 */
public enum AlarmPolicy {

    /** No VALARM is written at all. Matches Outlook's "no reminder" import behaviour. */
    STRIP,

    /**
     * Carry the source event's own alarms through. Only possible for events
     * whose provider supplied raw ICS (ICS feeds and CalDAV, including iCloud) -
     * Google and Microsoft Graph snapshots are JSON and carry no VALARM to copy,
     * so those events export with no reminder regardless.
     */
    PASSTHROUGH,

    /** Replace whatever the source had with one DISPLAY reminder, a fixed number of minutes before the start. */
    FIXED
}
