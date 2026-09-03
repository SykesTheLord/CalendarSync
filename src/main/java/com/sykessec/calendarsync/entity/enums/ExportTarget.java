package com.sykessec.calendarsync.entity.enums;

/**
 * Which calendar client a published feed's output is tuned for.
 *
 * The properties that control how an event *appears* to a subscriber are not
 * all standard. Privacy and busy/free are RFC 5545 ({@code CLASS},
 * {@code TRANSP}) and understood by every client; "out of office" as a state
 * distinct from plain "busy" exists only as a Microsoft extension
 * ({@code X-MICROSOFT-CDO-BUSYSTATUS}, MS-OXCICAL:
 * https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxcical/cd68eae7-ed65-4dd3-8ea7-ad585c76c736).
 *
 * RFC 5545 requires a client to ignore x-properties it does not recognise, so
 * UNIVERSAL - which emits the Microsoft extension alongside the standard
 * properties - is safe to hand to Google and Proton as well, and is the
 * recommended setting. The single-client targets exist for feeds where the
 * output should contain nothing the receiving client won't act on, which
 * matters when the feed is being diffed, archived or reviewed by hand.
 */
public enum ExportTarget {

    /** Standard properties plus Microsoft's extension. Safe everywhere; the only setting that can express OOF. */
    UNIVERSAL,

    /** Google Calendar: RFC 5545 properties only - Google ignores {@code X-MICROSOFT-*}. */
    GOOGLE,

    /** Outlook / Exchange / Microsoft 365: RFC 5545 properties plus {@code X-MICROSOFT-CDO-BUSYSTATUS}. */
    OUTLOOK,

    /** Proton Calendar: RFC 5545 properties only - Proton ignores {@code X-MICROSOFT-*}. */
    PROTON;

    /**
     * Whether {@code X-MICROSOFT-CDO-BUSYSTATUS} is written. Emitting it for a
     * client that ignores it is harmless, but a feed pinned to GOOGLE or PROTON
     * has explicitly asked for output without it.
     */
    public boolean emitsMicrosoftExtensions() {
        return this == UNIVERSAL || this == OUTLOOK;
    }
}
