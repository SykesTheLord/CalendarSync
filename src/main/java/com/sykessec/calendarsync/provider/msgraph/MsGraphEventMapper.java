package com.sykessec.calendarsync.provider.msgraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.Attendee;
import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.Event;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * MS Graph's kiota-generated model classes aren't directly JSON-serializable
 * via a JsonFactory the way Google's are, so snapshots are built/parsed with
 * a plain Jackson ObjectMapper against the model's own getters/setters.
 */
@Component
public class MsGraphEventMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Strips provider-generated fields (id, etag, createdDateTime,
     * lastModifiedDateTime, iCalUId) before the event is used as a
     * deletion_audit snapshot, per the spec's snapshot-cleaning rule for
     * MS Graph events.
     */
    public ProviderEvent toProviderEvent(Event event, String calendarName) throws ProviderException {
        List<String> attendees = event.getAttendees() == null ? List.of()
                : event.getAttendees().stream()
                        .map(Attendee::getEmailAddress)
                        .filter(java.util.Objects::nonNull)
                        .map(com.microsoft.graph.models.EmailAddress::getAddress)
                        .toList();
        boolean recurring = event.getRecurrence() != null;

        try {
            SnapshotEvent clean = SnapshotEvent.from(event);
            String snapshot = objectMapper.writeValueAsString(clean);

            return new ProviderEvent(
                    event.getId(),
                    event.getSubject(),
                    event.getBody() == null ? null : event.getBody().getContent(),
                    event.getLocation() == null ? null : event.getLocation().getDisplayName(),
                    attendees,
                    toInstant(event.getStart()),
                    toInstant(event.getEnd()),
                    recurring,
                    calendarName,
                    SnapshotFormat.MS_GRAPH_JSON,
                    snapshot);
        } catch (IOException e) {
            throw new ProviderException("Failed to serialize MS Graph event snapshot: " + e.getMessage(), e);
        }
    }

    /** Deserializes a stored snapshot back into an Event body for events().post(). */
    public Event fromSnapshot(String snapshotJson) throws ProviderException {
        try {
            SnapshotEvent clean = objectMapper.readValue(snapshotJson, SnapshotEvent.class);
            return clean.toEvent();
        } catch (IOException e) {
            throw new ProviderException("Failed to parse stored MS Graph event snapshot: " + e.getMessage(), e);
        }
    }

    private Instant toInstant(DateTimeTimeZone dtz) {
        if (dtz == null || dtz.getDateTime() == null) {
            return null;
        }
        ZoneId zone = dtz.getTimeZone() == null ? ZoneOffset.UTC : parseZone(dtz.getTimeZone());
        return LocalDateTime.parse(dtz.getDateTime()).atZone(zone).toInstant();
    }

    private ZoneId parseZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (Exception e) {
            // MS Graph sometimes returns Windows time zone names ical4j/java.time
            // don't recognize (e.g. "Pacific Standard Time") - UTC is a safe
            // fallback rather than failing the whole sync over one event.
            return ZoneOffset.UTC;
        }
    }

    /**
     * A minimal, plain-Java mirror of the Event fields this app actually
     * uses - Jackson can serialize/deserialize this directly, unlike the
     * kiota-generated Event class which expects its own serialization
     * machinery (SerializationWriter/ParseNode) rather than reflection.
     */
    static final class SnapshotEvent {
        public String subject;
        public String bodyContent;
        public String bodyContentType;
        public String locationDisplayName;
        public String startDateTime;
        public String startTimeZone;
        public String endDateTime;
        public String endTimeZone;
        public List<String> attendeeEmails;

        static SnapshotEvent from(Event event) {
            SnapshotEvent s = new SnapshotEvent();
            s.subject = event.getSubject();
            if (event.getBody() != null) {
                s.bodyContent = event.getBody().getContent();
                s.bodyContentType = event.getBody().getContentType() == null ? null
                        : event.getBody().getContentType().name();
            }
            if (event.getLocation() != null) {
                s.locationDisplayName = event.getLocation().getDisplayName();
            }
            if (event.getStart() != null) {
                s.startDateTime = event.getStart().getDateTime();
                s.startTimeZone = event.getStart().getTimeZone();
            }
            if (event.getEnd() != null) {
                s.endDateTime = event.getEnd().getDateTime();
                s.endTimeZone = event.getEnd().getTimeZone();
            }
            s.attendeeEmails = event.getAttendees() == null ? List.of()
                    : event.getAttendees().stream()
                            .map(Attendee::getEmailAddress)
                            .filter(java.util.Objects::nonNull)
                            .map(com.microsoft.graph.models.EmailAddress::getAddress)
                            .toList();
            return s;
        }

        Event toEvent() {
            Event event = new Event();
            event.setSubject(subject);
            if (bodyContent != null) {
                com.microsoft.graph.models.ItemBody body = new com.microsoft.graph.models.ItemBody();
                body.setContent(bodyContent);
                if (bodyContentType != null) {
                    body.setContentType(com.microsoft.graph.models.BodyType.forValue(bodyContentType));
                }
                event.setBody(body);
            }
            if (locationDisplayName != null) {
                com.microsoft.graph.models.Location location = new com.microsoft.graph.models.Location();
                location.setDisplayName(locationDisplayName);
                event.setLocation(location);
            }
            if (startDateTime != null) {
                DateTimeTimeZone start = new DateTimeTimeZone();
                start.setDateTime(startDateTime);
                start.setTimeZone(startTimeZone);
                event.setStart(start);
            }
            if (endDateTime != null) {
                DateTimeTimeZone end = new DateTimeTimeZone();
                end.setDateTime(endDateTime);
                end.setTimeZone(endTimeZone);
                event.setEnd(end);
            }
            if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
                event.setAttendees(attendeeEmails.stream().map(email -> {
                    Attendee attendee = new Attendee();
                    com.microsoft.graph.models.EmailAddress address = new com.microsoft.graph.models.EmailAddress();
                    address.setAddress(email);
                    attendee.setEmailAddress(address);
                    return attendee;
                }).toList());
            }
            return event;
        }
    }
}
