package com.sykessec.calendarsync.provider.caldav;

import org.springframework.http.HttpHeaders;

/**
 * Wraps a raw CalDAV HTTP response without throwing on non-2xx - callers
 * (CalDavProvider, CalDavDiscoveryService) decide what's fatal, since a 404
 * or 3xx is often meaningful CalDAV protocol signal rather than an error.
 */
public record CalDavResponse(int status, HttpHeaders headers, String body) {

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
