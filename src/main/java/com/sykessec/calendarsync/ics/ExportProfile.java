package com.sykessec.calendarsync.ics;

import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-feed settings that decide how each retained VEVENT is written out -
 * privacy, availability and reminders - separated from the PublishedFeed entity
 * so IcsCalendarMapper can be exercised without a database row and so the
 * defaults live in exactly one place.
 *
 * DEFAULT reproduces the output this app produced before export settings
 * existed: no CLASS, no TRANSP, no Microsoft extension and no VALARM. Existing
 * feeds therefore serve byte-identical calendars until someone changes them.
 */
public record ExportProfile(
        ExportTarget target,
        EventClassification classification,
        EventBusyStatus busyStatus,
        AlarmPolicy alarmPolicy,
        int alarmMinutesBefore
) {

    /** Longest offset a fixed reminder may use: four weeks, matching the widest range mainstream clients accept. */
    public static final int MAX_ALARM_MINUTES = 40320;

    /**
     * Microsoft's busy-status extension. It lives here rather than in the
     * mapper because this record is what decides whether it is written, and
     * because the settings UI names it when showing what a feed will emit.
     */
    public static final String X_MICROSOFT_CDO_BUSYSTATUS = "X-MICROSOFT-CDO-BUSYSTATUS";

    public static final ExportProfile DEFAULT = new ExportProfile(ExportTarget.UNIVERSAL,
            EventClassification.UNCHANGED, EventBusyStatus.UNCHANGED, AlarmPolicy.STRIP, 15);

    /**
     * A null enum here would mean a column that is NOT NULL in the schema came
     * back empty, so falling back to the default is the only sane reading - but
     * an out-of-range reminder offset is rejected rather than clamped, because
     * silently exporting a reminder at a time nobody asked for is worse than
     * refusing to save the setting.
     */
    public ExportProfile {
        target = target == null ? ExportTarget.UNIVERSAL : target;
        classification = classification == null ? EventClassification.UNCHANGED : classification;
        busyStatus = busyStatus == null ? EventBusyStatus.UNCHANGED : busyStatus;
        alarmPolicy = alarmPolicy == null ? AlarmPolicy.STRIP : alarmPolicy;
        if (alarmMinutesBefore < 0 || alarmMinutesBefore > MAX_ALARM_MINUTES) {
            throw new IllegalArgumentException(
                    "A fixed reminder must be between 0 and " + MAX_ALARM_MINUTES + " minutes before the event");
        }
    }

    public static ExportProfile from(PublishedFeed feed) {
        if (feed == null) {
            return DEFAULT;
        }
        return new ExportProfile(feed.getExportTarget(), feed.getEventClassification(), feed.getEventBusyStatus(),
                feed.getAlarmPolicy(), feed.getAlarmMinutesBefore());
    }

    /** Applies this profile's values to a feed row, ready to save. */
    public void applyTo(PublishedFeed feed) {
        feed.setExportTarget(target);
        feed.setEventClassification(classification);
        feed.setEventBusyStatus(busyStatus);
        feed.setAlarmPolicy(alarmPolicy);
        feed.setAlarmMinutesBefore(alarmMinutesBefore);
    }

    /**
     * True when X-MICROSOFT-CDO-BUSYSTATUS should be written: the target has to
     * emit Microsoft extensions AND the chosen busy status has to have a value
     * to express (UNCHANGED does not).
     */
    public boolean emitsMicrosoftBusyStatus() {
        return target.emitsMicrosoftExtensions() && busyStatus.microsoftBusyStatus() != null;
    }

    /**
     * True when the chosen busy status can only be expressed by the Microsoft
     * extension and this target won't emit it - i.e. OUT_OF_OFFICE degrading to
     * plain busy. The UI says so rather than letting a user pick Out of Office
     * for a Google feed and assume it arrived.
     */
    public boolean busyStatusIsDowngraded() {
        return busyStatus == EventBusyStatus.OUT_OF_OFFICE && !target.emitsMicrosoftExtensions();
    }

    /**
     * The exact property lines this profile stamps on every exported event, for
     * the settings UI to show. Four checkboxes and a dropdown do not tell
     * anyone what will actually land in the file; these lines do.
     */
    public List<String> exportedPropertyLines() {
        List<String> lines = new ArrayList<>();
        if (classification.icalValue() != null) {
            lines.add("CLASS:" + classification.icalValue());
        }
        if (busyStatus.transpValue() != null) {
            lines.add("TRANSP:" + busyStatus.transpValue());
        }
        if (emitsMicrosoftBusyStatus()) {
            lines.add(X_MICROSOFT_CDO_BUSYSTATUS + ":" + busyStatus.microsoftBusyStatus());
        }
        switch (alarmPolicy) {
            case FIXED -> lines.add("VALARM: ACTION:DISPLAY, TRIGGER:-PT" + alarmMinutesBefore + "M");
            case PASSTHROUGH -> lines.add("VALARM: copied from the source event");
            case STRIP -> lines.add("VALARM: none");
        }
        return lines;
    }
}
