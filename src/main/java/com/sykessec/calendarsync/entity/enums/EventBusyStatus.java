package com.sykessec.calendarsync.entity.enums;

import net.fortuna.ical4j.model.property.Transp;

/**
 * How an exported event should affect the subscriber's availability.
 *
 * This is deliberately one setting that writes two properties, because neither
 * one alone says what a user means. {@code TRANSP} is the standard property and
 * is all a client is guaranteed to understand, but it has exactly two values -
 * OPAQUE (blocks time) and TRANSPARENT (does not). "Out of office" and
 * "tentative" are both OPAQUE to it, so TRANSP alone cannot distinguish them
 * (https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxcical/54d3000c-a6b3-43c0-b4b0-543578f86fa0).
 *
 * {@code X-MICROSOFT-CDO-BUSYSTATUS} carries the finer state for Outlook and
 * Exchange, which is why OUT_OF_OFFICE emits both: the extension so Outlook
 * shows the appointment as Out of Office, and TRANSP:OPAQUE as the fallback
 * every other client - and Outlook itself, if the extension fails to import -
 * reads as "this time is taken".
 *
 * A target that does not emit Microsoft extensions therefore degrades
 * OUT_OF_OFFICE to plain busy rather than losing the event's effect entirely.
 */
public enum EventBusyStatus {

    /** Emit neither property - the receiving client decides. */
    UNCHANGED(null, null),

    FREE(Transp.VALUE_TRANSPARENT, "FREE"),

    BUSY(Transp.VALUE_OPAQUE, "BUSY"),

    TENTATIVE(Transp.VALUE_OPAQUE, "TENTATIVE"),

    /** Outlook's Out of Office state; everywhere else this reads as busy. */
    OUT_OF_OFFICE(Transp.VALUE_OPAQUE, "OOF");

    private final String transpValue;
    private final String microsoftBusyStatus;

    EventBusyStatus(String transpValue, String microsoftBusyStatus) {
        this.transpValue = transpValue;
        this.microsoftBusyStatus = microsoftBusyStatus;
    }

    /** The TRANSP property value, or null when nothing should be written. */
    public String transpValue() {
        return transpValue;
    }

    /** The X-MICROSOFT-CDO-BUSYSTATUS value, or null when nothing should be written. */
    public String microsoftBusyStatus() {
        return microsoftBusyStatus;
    }
}
