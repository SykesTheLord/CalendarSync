package com.sykessec.calendarsync.provider.caldav;

import org.springframework.http.HttpHeaders;

import java.net.URI;

/**
 * Wraps a raw CalDAV HTTP response without throwing on non-2xx - callers
 * (CalDavProvider, CalDavDiscoveryService) decide what's fatal, since a 404
 * or 3xx is often meaningful CalDAV protocol signal rather than an error.
 *
 * finalUri is the URI this response actually came from, which is not
 * necessarily the one the caller asked for: CalDavClient follows same-site
 * redirects itself. Discovery has to resolve hrefs out of a multistatus body
 * against the URI that body came from, not against the one that redirected -
 * a relative href resolved against the pre-redirect URI points at the wrong
 * collection, and iCloud's partition hop makes that the normal case rather
 * than the exotic one.
 */
public record CalDavResponse(int status, HttpHeaders headers, String body, URI finalUri) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isRedirect() {
        return status >= 300 && status < 400;
    }

    public String etag() {
        return headers == null ? null : headers.getFirst("ETag");
    }

    public String location() {
        return headers == null ? null : headers.getFirst("Location");
    }
}
