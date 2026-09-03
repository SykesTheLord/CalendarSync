package com.sykessec.calendarsync.ics;

import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Clazz;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.validate.ValidationException;
import net.fortuna.ical4j.validate.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;

/**
 * ProviderEvent <-> ICS text, via ical4j. Used both for import (parsing a
 * fetched ICS feed's VEVENTs - IcsSourceProvider) and export (building a
 * fresh VCALENDAR from any mix of provider-normalized events -
 * IcsExportService). Export deliberately builds VEVENTs from ProviderEvent's
 * normalized fields rather than reusing raw snapshot payloads, since a
 * published_feed can combine calendars from any provider mix and only
 * ICS-sourced events even have raw ICS text to begin with.
 *
 * Building rather than forwarding is also what makes an ExportProfile
 * enforceable: because every exported VEVENT starts empty, the profile's
 * properties are the only CLASS, TRANSP, X-MICROSOFT-CDO-BUSYSTATUS and VALARM
 * that can appear. There is no source property to conflict with and no stray
 * second instance to remove.
 */
@Component
public class IcsCalendarMapper {

    private static final Logger log = LoggerFactory.getLogger(IcsCalendarMapper.class);

    private static final String PROD_ID = "-//CalendarSync//CalendarSync 0.1//EN";

    /** DESCRIPTION is mandatory in a DISPLAY VALARM, and some clients show it verbatim. */
    private static final String FIXED_ALARM_FALLBACK_TEXT = "Reminder";

    /** Parses every VEVENT in a fetched ICS feed. */
    public List<ProviderEvent> parseFeed(String icsText, String calendarName) throws ProviderException {
        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new StringReader(icsText));
        } catch (IOException | ParserException e) {
            throw new ProviderException("Failed to parse ICS feed: " + e.getMessage(), e);
        }

        return calendar.getComponents().stream()
                .filter(VEvent.class::isInstance)
                .map(VEvent.class::cast)
                .map(vevent -> toProviderEvent(vevent, calendarName))
                .toList();
    }

    private ProviderEvent toProviderEvent(VEvent vevent, String calendarName) {
        String uid = vevent.getProperty(Property.UID).map(Property::getValue).orElse(null);
        String title = vevent.getSummary() == null ? null : vevent.getSummary().getValue();
        String description = vevent.getDescription() == null ? null : vevent.getDescription().getValue();
        String location = vevent.getLocation() == null ? null : vevent.getLocation().getValue();
        List<String> attendees = vevent.getAttendees().stream().map(Property::getValue).toList();
        Instant start = vevent.getStartDate().map(p -> toInstant(p.getDate())).orElse(null);
        Instant end = vevent.getEndDate().map(p -> toInstant(p.getDate())).orElse(null);
        boolean recurring = vevent.getProperty(Property.RRULE).isPresent();

        String snapshot;
        try {
            StringWriter writer = new StringWriter();
            Calendar wrapper = new Calendar();
            wrapper.add(new ProdId(PROD_ID));
            wrapper.add(newVersion2());
            wrapper.add(vevent);
            new CalendarOutputter().output(wrapper, writer);
            snapshot = writer.toString();
        } catch (IOException | ValidationException e) {
            snapshot = null;
        }

        return new ProviderEvent(uid, title, description, location, attendees, start, end, recurring,
                calendarName, snapshot == null ? null : SnapshotFormat.ICS, snapshot);
    }

    /**
     * Builds a complete VCALENDAR document from a set of normalized events,
     * e.g. for a published feed, applying that feed's export profile to every
     * event in it.
     */
    public byte[] buildFeed(List<ProviderEvent> events, ExportProfile profile) throws ProviderException {
        ExportProfile effective = profile == null ? ExportProfile.DEFAULT : profile;
        Calendar calendar = new Calendar();
        calendar.add(new ProdId(PROD_ID));
        calendar.add(newVersion2());
        calendar.add(new CalScale(CalScale.VALUE_GREGORIAN));

        for (ProviderEvent event : events) {
            calendar.add(toVEvent(event, effective));
        }

        try {
            StringWriter writer = new StringWriter();
            new CalendarOutputter().output(calendar, writer);
            return writer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException | ValidationException e) {
            throw new ProviderException("Failed to build ICS feed: " + e.getMessage(), e);
        }
    }

    private Version newVersion2() {
        Version version = new Version();
        version.setValue(Version.VALUE_2_0);
        return version;
    }

    private VEvent toVEvent(ProviderEvent event, ExportProfile profile) {
        VEvent vevent = new VEvent();
        vevent.add(new Uid(event.uid() == null ? java.util.UUID.randomUUID().toString() : event.uid()));
        if (event.start() != null) {
            vevent.add(new DtStart<>(event.start()));
        }
        if (event.end() != null) {
            vevent.add(new DtEnd<>(event.end()));
        }
        if (event.title() != null) {
            vevent.add(new Summary(event.title()));
        }
        if (event.description() != null) {
            vevent.add(new Description(event.description()));
        }
        if (event.location() != null) {
            vevent.add(new Location(event.location()));
        }
        if (event.attendees() != null) {
            for (String attendee : event.attendees()) {
                String value = attendee.contains(":") ? attendee : "mailto:" + attendee;
                vevent.add(new Attendee(value));
            }
        }
        applyExportProfile(vevent, event, profile);
        return vevent;
    }

    /**
     * Stamps the feed's privacy, availability and reminder settings onto one
     * exported event.
     *
     * replace() rather than add() for the three properties, even though this
     * VEVENT was built empty a few lines ago and cannot already carry them: the
     * single-instance rule is then a property of this method rather than of the
     * order the builder happens to run in, so adding a source-derived CLASS or
     * TRANSP above can never quietly produce a duplicate. Duplicates matter -
     * Outlook's import takes one instance and a second is undefined behaviour.
     */
    private void applyExportProfile(VEvent vevent, ProviderEvent event, ExportProfile profile) {
        if (profile.classification().icalValue() != null) {
            vevent.replace(new Clazz(profile.classification().icalValue()));
        }
        if (profile.busyStatus().transpValue() != null) {
            vevent.replace(new Transp(profile.busyStatus().transpValue()));
        }
        if (profile.emitsMicrosoftBusyStatus()) {
            // MS-OXCICAL allows exactly one instance of this per event, which
            // replace() guarantees regardless of what came before.
            vevent.replace(new XProperty(ExportProfile.X_MICROSOFT_CDO_BUSYSTATUS,
                    profile.busyStatus().microsoftBusyStatus()));
        }
        for (VAlarm alarm : alarmsFor(event, profile)) {
            vevent.add(alarm);
        }
    }

    /**
     * The VALARMs this event should export with. STRIP - the default - returns
     * nothing, which is also what a freshly built VEVENT already has, so the
     * "no reminders" guarantee is structural rather than a removal step that
     * could be missed.
     */
    private List<VAlarm> alarmsFor(ProviderEvent event, ExportProfile profile) {
        return switch (profile.alarmPolicy()) {
            case STRIP -> List.of();
            case FIXED -> List.of(fixedAlarm(event, profile.alarmMinutesBefore()));
            case PASSTHROUGH -> sourceAlarms(event);
        };
    }

    private VAlarm fixedAlarm(ProviderEvent event, int minutesBefore) {
        // Negative offset: TRIGGER is relative to DTSTART, so "15 minutes
        // before" is -PT15M. A positive value would fire after the event began.
        VAlarm alarm = new VAlarm();
        alarm.add(new Trigger(Duration.ofMinutes(-minutesBefore)));
        alarm.add(new Action(Action.VALUE_DISPLAY));
        alarm.add(new Description(event.title() == null || event.title().isBlank()
                ? FIXED_ALARM_FALLBACK_TEXT : event.title()));
        return alarm;
    }

    /**
     * Copies the alarms out of the event's raw ICS snapshot. Only ICS-shaped
     * snapshots have any - Google and Microsoft Graph events carry JSON, whose
     * reminder fields have no VALARM to lift - so those export unreminded, and
     * PASSTHROUGH is documented as ICS/CalDAV-only rather than pretending
     * otherwise.
     *
     * A snapshot that won't parse, or an alarm the library considers invalid,
     * is skipped instead of propagated. One malformed reminder on one event of
     * one source calendar must not turn the whole feed into a 502 - the
     * subscriber loses a reminder, not their calendar.
     */
    private List<VAlarm> sourceAlarms(ProviderEvent event) {
        if (event.rawFormat() != SnapshotFormat.ICS || event.rawPayload() == null || event.rawPayload().isBlank()) {
            return List.of();
        }
        List<VAlarm> alarms = new ArrayList<>();
        try {
            Calendar parsed = new CalendarBuilder().build(new StringReader(event.rawPayload()));
            for (VEvent source : parsed.<VEvent>getComponents(net.fortuna.ical4j.model.Component.VEVENT)) {
                for (VAlarm alarm : source.getAlarms()) {
                    if (isUsable(alarm)) {
                        alarms.add(alarm);
                    } else {
                        log.warn("Dropping an invalid VALARM from event {} while exporting - "
                                + "the event itself is unaffected", event.uid());
                    }
                }
            }
        } catch (IOException | ParserException e) {
            log.warn("Could not read reminders from the ICS snapshot of event {}: {}", event.uid(), e.getMessage());
            return List.of();
        }
        return alarms;
    }

    private boolean isUsable(VAlarm alarm) {
        try {
            ValidationResult result = alarm.validate();
            return !result.hasErrors();
        } catch (ValidationException e) {
            return false;
        }
    }

    private Instant toInstant(Temporal temporal) {
        if (temporal instanceof Instant instant) {
            return instant;
        }
        if (temporal instanceof ZonedDateTime zdt) {
            return zdt.toInstant();
        }
        if (temporal instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (temporal instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneOffset.UTC).toInstant();
        }
        if (temporal instanceof LocalDate ld) {
            return ld.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        throw new IllegalArgumentException("Unsupported temporal type: " + temporal.getClass());
    }
}
