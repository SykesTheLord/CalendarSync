package com.sykessec.calendarsync.entity.enums;

import net.fortuna.ical4j.model.property.Clazz;

/**
 * The {@code CLASS} property stamped on every exported event, or UNCHANGED to
 * emit none. Outlook/Exchange map {@code CLASS:PRIVATE} to their private
 * sensitivity setting and treat an event with no CLASS as public
 * (https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxcical/c3c3ec34-9c17-4542-8313-19416e6c6830),
 * so leaving it off is a decision, not a neutral default.
 *
 * Worth being clear about what this buys: CLASS is a display and sensitivity
 * flag the receiving client is trusted to honour. It is not encryption - the
 * ICS body still carries the summary, description and attendees in clear text,
 * and anyone holding the feed token can read them. Use a rule to strip events
 * that must not leave this server at all.
 */
public enum EventClassification {

    /** Emit no CLASS property - whatever the receiving client defaults to wins. */
    UNCHANGED(null),

    PUBLIC(Clazz.VALUE_PUBLIC),

    PRIVATE(Clazz.VALUE_PRIVATE),

    CONFIDENTIAL(Clazz.VALUE_CONFIDENTIAL);

    private final String icalValue;

    EventClassification(String icalValue) {
        this.icalValue = icalValue;
    }

    /** The CLASS property value, or null when nothing should be written. */
    public String icalValue() {
        return icalValue;
    }
}
