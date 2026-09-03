package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.provider.ProviderException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Hand-rolled CalDAV wire protocol: PROPFIND discovery, REPORT
 * calendar-query, GET, PUT, DELETE. Deliberately low-level - callers
 * (CalDavDiscoveryService, CalDavProvider) own all CalDAV semantics; this
 * class only knows how to send the five HTTP calls and hand back a response
 * without throwing on non-2xx, since 3xx/404 are often meaningful protocol
 * signal here rather than failures.
 */
@Component
public class CalDavClient {

    private static final MediaType APPLICATION_XML_UTF8 = MediaType.valueOf("application/xml;charset=utf-8");
    private static final MediaType TEXT_CALENDAR = MediaType.valueOf("text/calendar;charset=utf-8");

    private final RestClient restClient;

    public CalDavClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public CalDavResponse propfind(URI uri, int depth, String requestBodyXml, CalDavCredentials credentials)
            throws ProviderException {
        return exchange(HttpMethod.valueOf("PROPFIND"), uri, credentials, headers -> {
            headers.set("Depth", String.valueOf(depth));
            headers.setContentType(APPLICATION_XML_UTF8);
        }, requestBodyXml);
    }

    public CalDavResponse reportCalendarQuery(URI calendarCollectionUri, String requestBodyXml,
                                               CalDavCredentials credentials) throws ProviderException {
        return exchange(HttpMethod.valueOf("REPORT"), calendarCollectionUri, credentials, headers -> {
            headers.set("Depth", "1");
            headers.setContentType(APPLICATION_XML_UTF8);
        }, requestBodyXml);
    }

    public CalDavResponse getEvent(URI resourceUri, CalDavCredentials credentials) throws ProviderException {
        return exchange(HttpMethod.GET, resourceUri, credentials, headers -> { }, null);
    }

    public CalDavResponse putEvent(URI resourceUri, String icsBody, String ifMatchEtag, CalDavCredentials credentials)
            throws ProviderException {
        return exchange(HttpMethod.PUT, resourceUri, credentials, headers -> {
            headers.setContentType(TEXT_CALENDAR);
            if (ifMatchEtag != null) {
                headers.set(HttpHeaders.IF_MATCH, ifMatchEtag);
            }
        }, icsBody);
    }

    public CalDavResponse deleteEvent(URI resourceUri, String ifMatchEtag, CalDavCredentials credentials)
            throws ProviderException {
        return exchange(HttpMethod.DELETE, resourceUri, credentials, headers -> {
            if (ifMatchEtag != null) {
                headers.set(HttpHeaders.IF_MATCH, ifMatchEtag);
            }
        }, null);
    }

    private CalDavResponse exchange(HttpMethod method, URI uri, CalDavCredentials credentials,
                                     java.util.function.Consumer<HttpHeaders> headerCustomizer, String body)
            throws ProviderException {
        try {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(uri)
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader(credentials));
                        headerCustomizer.accept(headers);
                    });
            if (body != null) {
                spec.body(body);
            }
            return spec.exchange((request, response) -> {
                String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                return new CalDavResponse(response.getStatusCode().value(), response.getHeaders(), responseBody);
            }, true);
        } catch (RuntimeException e) {
            throw new ProviderException("CalDAV " + method + " " + uri + " failed: " + e.getMessage(), e);
        }
    }

    private String basicAuthHeader(CalDavCredentials credentials) {
        String raw = credentials.username() + ":" + credentials.password();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
