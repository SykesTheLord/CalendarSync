package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;

/**
 * Sequences CalDAV bootstrap: fixed entry host -> PROPFIND current-user-principal
 * -> PROPFIND calendar-home-set on the principal URL -> the calendar-home-set
 * response's host is the real partition host all subsequent calls must
 * target (e.g. iCloud's numbered "pNN-caldav.icloud.com" hosts) - this is
 * exactly why no partition host is ever hardcoded here. iCloud and generic
 * CalDAV differ only in the fixed entry point; everything downstream is the
 * same code path.
 */
@Service
public class CalDavDiscoveryService {

    private static final URI ICLOUD_ENTRY = URI.create("https://caldav.icloud.com/");
    private static final int MAX_REDIRECTS = 5;

    private final CalDavClient client;

    public CalDavDiscoveryService(CalDavClient client) {
        this.client = client;
    }

    public URI entryPointFor(CalendarConnection connection) throws ProviderException {
        if (connection.getProvider() == ProviderType.ICLOUD) {
            return ICLOUD_ENTRY;
        }
        if (connection.getCaldavBaseUrl() == null || connection.getCaldavBaseUrl().isBlank()) {
            throw new ProviderException("Connection has no CalDAV base URL configured");
        }
        return URI.create(connection.getCaldavBaseUrl());
    }

    /** Full bootstrap: returns the resolved, absolute calendar-home-set URI. */
    public URI discoverCalendarHomeSet(CalendarConnection connection, CalDavCredentials credentials)
            throws ProviderException {
        URI entry = entryPointFor(connection);

        PropfindResult principal = propfindFollowingRedirects(entry, 0,
                CalDavXmlSupport.currentUserPrincipalRequest(), credentials);
        requireSuccess(principal.response(), "current-user-principal");
        String principalHref = CalDavXmlSupport.parseCurrentUserPrincipal(principal.response().body())
                .orElseThrow(() -> new ProviderException("No current-user-principal in PROPFIND response"));
        URI principalUri = CalDavUris.resolveWithinSite(principal.finalUri(), principalHref);

        PropfindResult homeSet = propfindFollowingRedirects(principalUri, 0,
                CalDavXmlSupport.calendarHomeSetRequest(), credentials);
        requireSuccess(homeSet.response(), "calendar-home-set");
        String homeSetHref = CalDavXmlSupport.parseCalendarHomeSet(homeSet.response().body())
                .orElseThrow(() -> new ProviderException("No calendar-home-set in PROPFIND response"));

        return CalDavUris.resolveWithinSite(homeSet.finalUri(), homeSetHref);
    }

    /** Depth:1 PROPFIND on the home-set, returning each child calendar collection's absolute URI + name. */
    public List<CalDavXmlSupport.DiscoveredCalendar> discoverCalendars(URI calendarHomeSetUri,
                                                                        CalDavCredentials credentials)
            throws ProviderException {
        CalDavResponse response = client.propfind(calendarHomeSetUri, 1,
                CalDavXmlSupport.calendarCollectionListRequest(), credentials);
        requireSuccess(response, "calendar collection listing");

        List<CalDavXmlSupport.DiscoveredCalendar> discovered = new java.util.ArrayList<>();
        for (CalDavXmlSupport.DiscoveredCalendar c : CalDavXmlSupport.parseCalendarCollections(response.body())) {
            discovered.add(new CalDavXmlSupport.DiscoveredCalendar(
                    CalDavUris.resolveWithinSite(calendarHomeSetUri, c.href()).toString(), c.displayName()));
        }
        return discovered;
    }

    private PropfindResult propfindFollowingRedirects(URI uri, int depth, String body, CalDavCredentials credentials)
            throws ProviderException {
        URI current = uri;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            CalDavResponse response = client.propfind(current, depth, body, credentials);
            if (!response.isRedirect() || response.location() == null) {
                return new PropfindResult(response, current);
            }
            // The next hop carries the user's CalDAV password, so it has to
            // stay inside the site they pointed this connection at - a hostile
            // or compromised server must not be able to redirect the credential
            // to a host of its choosing.
            current = CalDavUris.resolveWithinSite(current, response.location());
        }
        throw new ProviderException("Too many redirects discovering CalDAV endpoint at " + uri);
    }

    private void requireSuccess(CalDavResponse response, String what) throws ProviderException {
        if (!response.isSuccess()) {
            throw new ProviderException("PROPFIND for " + what + " failed: HTTP " + response.status());
        }
    }

    private record PropfindResult(CalDavResponse response, URI finalUri) {
    }
}
