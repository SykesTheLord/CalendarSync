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

        // CalDavClient follows same-site redirects itself, so these are already
        // the final hop - response.finalUri() is what an href must be resolved
        // against, which is the whole reason it is carried back.
        CalDavResponse principal = client.propfind(entry, 0,
                CalDavXmlSupport.currentUserPrincipalRequest(), credentials);
        requireSuccess(principal, "current-user-principal");
        String principalHref = CalDavXmlSupport.parseCurrentUserPrincipal(principal.body())
                .orElseThrow(() -> new ProviderException("No current-user-principal in PROPFIND response"));
        URI principalUri = CalDavUris.resolveWithinSite(principal.finalUri(), principalHref);

        CalDavResponse homeSet = client.propfind(principalUri, 0,
                CalDavXmlSupport.calendarHomeSetRequest(), credentials);
        requireSuccess(homeSet, "calendar-home-set");
        String homeSetHref = CalDavXmlSupport.parseCalendarHomeSet(homeSet.body())
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
                    CalDavUris.resolveWithinSite(response.finalUri(), c.href()).toString(), c.displayName()));
        }
        return discovered;
    }

    private void requireSuccess(CalDavResponse response, String what) throws ProviderException {
        if (!response.isSuccess()) {
            throw new ProviderException("PROPFIND for " + what + " failed: HTTP " + response.status());
        }
    }
}
