package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.validate.ValidationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;

/** ICS text <-> ProviderEvent, via ical4j. Shared by iCloud and generic CalDAV - the same mapping either way. */
@Component
public class CalDavEventMapper {

    public ProviderEvent toProviderEvent(String icsText, String calendarName) throws ProviderException {
        VEvent vevent = firstVEvent(icsText);

        String uid = vevent.getProperty(Property.UID).map(Property::getValue).orElse(null);
        String title = vevent.getSummary() == null ? null : vevent.getSummary().getValue();
        String description = vevent.getDescription() == null ? null : vevent.getDescription().getValue();
        String location = vevent.getLocation() == null ? null : vevent.getLocation().getValue();
        List<String> attendees = vevent.getAttendees().stream()
                .map(Property::getValue)
                .map(CalDavEventMapper::stripMailto)
                .toList();
        Instant start = vevent.getStartDate().map(p -> toInstant(p.getDate())).orElse(null);
        Instant end = vevent.getEndDate().map(p -> toInstant(p.getDate())).orElse(null);
        boolean recurring = vevent.getProperty(Property.RRULE).isPresent();

        return new ProviderEvent(uid, title, description, location, attendees, start, end,
                recurring, calendarName, SnapshotFormat.ICS, icsText);
    }

    /**
     * For restore: CalDAV servers generally reject re-using a UID tied to a
     * deleted resource, so a restored event needs a fresh one before being
     * PUT to a new resource URL.
     */
    public String withFreshUid(String icsText, String newUid) throws ProviderException {
        try {
            Calendar calendar = new CalendarBuilder().build(new StringReader(icsText));
            VEvent vevent = firstVEvent(calendar);
            vevent.replace(new Uid(newUid));

            StringWriter writer = new StringWriter();
            new CalendarOutputter().output(calendar, writer);
            return writer.toString();
        } catch (IOException | ParserException | ValidationException e) {
            throw new ProviderException("Failed to rewrite event UID: " + e.getMessage(), e);
        }
    }

    private VEvent firstVEvent(String icsText) throws ProviderException {
        try {
            return firstVEvent(new CalendarBuilder().build(new StringReader(icsText)));
        } catch (IOException | ParserException e) {
            throw new ProviderException("Failed to parse ICS event: " + e.getMessage(), e);
        }
    }

    private VEvent firstVEvent(Calendar calendar) throws ProviderException {
        return calendar.getComponents().stream()
                .filter(VEvent.class::isInstance)
                .map(VEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new ProviderException("ICS payload has no VEVENT component"));
    }

    private static String stripMailto(String value) {
        if (value == null) {
            return null;
        }
        return value.regionMatches(true, 0, "mailto:", 0, 7) ? value.substring(7) : value;
    }

    private static Instant toInstant(Temporal temporal) {
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
