package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.DiscoveredCalendar;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.service.CredentialCipher;
import com.sykessec.calendarsync.util.TokenGenerator;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

/**
 * Shared implementation for ICLOUD and CALDAV - iCloud is just CalDAV with
 * fixed host discovery (caldav.icloud.com) and app-password auth; everything
 * downstream (event listing, delete, restore) is identical code, per spec.
 *
 * Delete = GET (capture raw ICS as the snapshot) then DELETE with If-Match
 * on the captured ETag, so a concurrent external edit isn't silently
 * clobbered. Restore = fresh UID + PUT to a new resource URL under the same
 * calendar collection, since CalDAV servers generally reject re-using a UID
 * tied to a deleted resource.
 */
@Service
public class CalDavProvider implements CalendarProvider {

    private final CalDavClient client;
    private final CalDavDiscoveryService discoveryService;
    private final CalDavEventMapper mapper;
    private final CredentialCipher credentialCipher;

    public CalDavProvider(CalDavClient client, CalDavDiscoveryService discoveryService, CalDavEventMapper mapper,
                           CredentialCipher credentialCipher) {
        this.client = client;
        this.discoveryService = discoveryService;
        this.mapper = mapper;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public boolean supports(ProviderType type) {
        return type == ProviderType.ICLOUD || type == ProviderType.CALDAV;
    }

    /**
     * Discovers every calendar collection under the connection's
     * calendar-home-set - used by the sync job to populate/update `calendar`
     * rows, since CalDAV has no separate "list calendars" API distinct from
     * PROPFIND on the home-set. CalDAV PROPFIND doesn't request a
     * privilege-related property here, so discovered calendars default to
     * writable=true - refining permission detection is a later improvement.
     */
    @Override
    public List<DiscoveredCalendar> discoverCalendars(CalendarConnection connection) throws ProviderException {
        CalDavCredentials credentials = credentialsFor(connection);
        URI homeSet = URI.create(discoveryService.discoverCalendarHomeSet(connection, credentials).toString());
        return discoveryService.discoverCalendars(homeSet, credentials).stream()
                .map(c -> new DiscoveredCalendar(c.href(), c.displayName(), true))
                .toList();
    }

    @Override
    public List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar, SyncState syncState)
            throws ProviderException {
        CalDavCredentials credentials = credentialsFor(connection);
        URI calendarUri = URI.create(calendar.getRemoteCalendarId());

        CalDavResponse response = client.reportCalendarQuery(calendarUri, CalDavXmlSupport.vEventQueryRequest(),
                credentials);
        if (!response.isSuccess()) {
            throw new ProviderException("REPORT calendar-query failed for " + calendarUri + ": HTTP " + response.status());
        }

        return CalDavXmlSupport.parseCalendarQueryResponse(response.body()).stream()
                .map(hrefAndEtag -> toProviderEvent(hrefAndEtag, calendar.getName()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ProviderEvent toProviderEvent(CalDavXmlSupport.HrefAndEtag hrefAndEtag, String calendarName) {
        try {
            return mapper.toProviderEvent(hrefAndEtag.calendarData(), calendarName);
        } catch (ProviderException e) {
            // Skip unparseable resources rather than failing the whole sync -
            // one malformed VEVENT shouldn't block every other event.
            return null;
        }
    }

    @Override
    public void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event)
            throws ProviderException {
        // Snapshot must already be captured in event.rawPayload() by the
        // caller (TrashService) before this method is ever invoked - this
        // method only performs the actual provider-side deletion.
        CalDavCredentials credentials = credentialsFor(connection);
        CalDavXmlSupport.HrefAndEtag resource = resolveResourceByUid(calendar, event.uid(), credentials);
        // The href comes out of the server's own multistatus body, so it is
        // only as trustworthy as the server - keep the DELETE (and the Basic
        // auth header on it) inside the site this calendar lives on.
        URI resourceUri = CalDavUris.resolveWithinSite(URI.create(calendar.getRemoteCalendarId()), resource.href());

        CalDavResponse deleteResponse = client.deleteEvent(resourceUri, resource.etag(), credentials);
        if (!deleteResponse.isSuccess() && deleteResponse.status() != 404) {
            throw new ProviderException("DELETE failed for " + resourceUri + ": HTTP " + deleteResponse.status());
        }
    }

    @Override
    public ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                                      SnapshotFormat snapshotFormat, String snapshotPayload) throws ProviderException {
        if (snapshotFormat != SnapshotFormat.ICS) {
            throw new ProviderException("CalDavProvider can only restore ICS snapshots, got " + snapshotFormat);
        }
        CalDavCredentials credentials = credentialsFor(connection);

        String freshUid = TokenGenerator.urlSafeToken(16) + "@calendarsync";
        String rewritten = mapper.withFreshUid(snapshotPayload, freshUid);

        URI collectionUri = collectionUriFor(calendar);
        URI resourceUri = collectionUri.resolve(freshUid + ".ics");

        CalDavResponse response = client.putEvent(resourceUri, rewritten, null, credentials);
        if (!response.isSuccess()) {
            throw new ProviderException("PUT failed restoring event to " + resourceUri + ": HTTP " + response.status());
        }

        return mapper.toProviderEvent(rewritten, calendar.getName());
    }

    /**
     * The ICS UID is not necessarily the resource filename (servers pick
     * their own resource naming), so deletion re-queries the collection and
     * matches by UID rather than guessing a URL - correct regardless of
     * server naming convention, at the cost of one extra round trip.
     */
    private CalDavXmlSupport.HrefAndEtag resolveResourceByUid(CalendarEntity calendar, String uid,
                                                                CalDavCredentials credentials) throws ProviderException {
        if (uid == null) {
            throw new ProviderException("Cannot resolve a CalDAV resource for an event with no uid");
        }
        URI collectionUri = URI.create(calendar.getRemoteCalendarId());
        CalDavResponse response = client.reportCalendarQuery(collectionUri, CalDavXmlSupport.vEventQueryRequest(),
                credentials);
        if (!response.isSuccess()) {
            throw new ProviderException("REPORT calendar-query failed for " + collectionUri + ": HTTP " + response.status());
        }
        return CalDavXmlSupport.parseCalendarQueryResponse(response.body()).stream()
                .filter(hae -> uid.equals(uidOf(hae.calendarData())))
                .findFirst()
                .orElseThrow(() -> new ProviderException("No CalDAV resource with UID " + uid
                        + " found in calendar " + calendar.getId()));
    }

    /**
     * URI.resolve() replaces the last path segment unless the base ends in a
     * slash, so a collection stored as ".../calendars/work" would have had a
     * restored event PUT to ".../calendars/{uid}.ics" - a sibling of the
     * collection rather than a member of it. Most servers hand back hrefs with
     * the trailing slash already, which is why this only bites on the ones
     * that don't.
     */
    private URI collectionUriFor(CalendarEntity calendar) {
        String raw = calendar.getRemoteCalendarId();
        return URI.create(raw.endsWith("/") ? raw : raw + "/");
    }

    private String uidOf(String icsText) {
        try {
            return mapper.toProviderEvent(icsText, null).uid();
        } catch (ProviderException e) {
            return null;
        }
    }

    /**
     * calendar_connection.encrypted_credentials holds "username:password"
     * (or an app-specific password for iCloud), decoded via CredentialCipher
     * (plain UTF-8 in dev, AES-GCM in prod - see calendarsync.db.encrypted).
     */
    private CalDavCredentials credentialsFor(CalendarConnection connection) throws ProviderException {
        byte[] raw = connection.getEncryptedCredentials();
        if (raw == null) {
            throw new ProviderException("Connection " + connection.getId() + " has no credentials configured");
        }
        String decoded = credentialCipher.decode(raw);
        int separator = decoded.indexOf(':');
        if (separator < 0) {
            throw new ProviderException("Connection " + connection.getId()
                    + " credentials must be in \"username:password\" form");
        }
        return new CalDavCredentials(decoded.substring(0, separator), decoded.substring(separator + 1));
    }
}
