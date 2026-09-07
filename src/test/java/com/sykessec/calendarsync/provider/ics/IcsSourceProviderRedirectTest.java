package com.sykessec.calendarsync.provider.ics;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.ics.IcsCalendarMapper;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A published calendar URL that redirects has to keep working.
 *
 * Nothing under this class follows redirects on its own: Spring's RestClient
 * resolves its request factory to a reactor-netty client whose default is
 * followRedirect(false), so a 302 fell through the non-2xx branch and the sync
 * reported "Failed to fetch ICS feed ...: HTTP 302" - permanently, on every
 * poll. A plain http:// address upgrading to https:// is enough to produce
 * that, so the affected sources never synced at all rather than syncing badly.
 *
 * Driven against a real local HTTP server rather than a stubbed RestClient,
 * because the defect was in what the HTTP client does with a real 3xx response
 * - a stub would have been written to the behaviour we assumed we had.
 */
class IcsSourceProviderRedirectTest {

    private static final String FEED = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:moved-event@example.com
            DTSTAMP:20260101T000000Z
            DTSTART:20260601T090000Z
            DTEND:20260601T093000Z
            SUMMARY:Behind a redirect
            END:VEVENT
            END:VCALENDAR
            """;

    private HttpServer server;
    private final AtomicInteger feedHits = new AtomicInteger();
    private IcsSourceProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/moved", redirectTo("/real.ics"));
        server.createContext("/hop-1", redirectTo("/hop-2"));
        server.createContext("/hop-2", redirectTo("/real.ics"));
        server.createContext("/elsewhere", redirectTo("file:///etc/passwd"));
        server.createContext("/loop", redirectTo("/loop"));
        server.createContext("/real.ics", exchange -> {
            feedHits.incrementAndGet();
            byte[] body = FEED.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/calendar");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        provider = new IcsSourceProvider(RestClient.builder(), new IcsCalendarMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void followsARedirectToTheRealFeed() throws Exception {
        List<ProviderEvent> events = provider.listEvents(connection(), calendarAt("/moved"), null);

        assertThat(events).singleElement()
                .extracting(ProviderEvent::uid).isEqualTo("moved-event@example.com");
        assertThat(feedHits).hasValue(1);
    }

    @Test
    void followsAChainOfRedirects() throws Exception {
        assertThat(provider.listEvents(connection(), calendarAt("/hop-1"), null)).hasSize(1);
    }

    @Test
    void aDirectFeedStillWorks() throws Exception {
        assertThat(provider.listEvents(connection(), calendarAt("/real.ics"), null)).hasSize(1);
    }

    /**
     * The scheme backstop. No credential rides on an ICS fetch, so a hop to
     * another host is allowed - but a redirect must not be able to walk the
     * fetch off HTTP entirely and turn a calendar sync into a local file read.
     */
    @Test
    void refusesToFollowARedirectOffHttp() {
        assertThatThrownBy(() -> provider.listEvents(connection(), calendarAt("/elsewhere"), null))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("non-HTTP");
    }

    @Test
    void givesUpOnARedirectLoopInsteadOfSpinning() {
        assertThatThrownBy(() -> provider.listEvents(connection(), calendarAt("/loop"), null))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("redirected more than");
    }

    private HttpHandler redirectTo(String location) {
        return exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        };
    }

    private CalendarConnection connection() {
        CalendarConnection connection = new CalendarConnection();
        connection.setDisplayName("Redirecting source");
        return connection;
    }

    /**
     * listEvents caches by calendar id, which is database-generated and has no
     * setter - correctly, since nothing should be able to reassign an entity's
     * identity. A real calendar is always persisted before it is synced, so the
     * id is only null here; setting it directly is cheaper than a Spring
     * context for a test about what an HTTP client does with a 302.
     */
    private CalendarEntity calendarAt(String path) {
        CalendarEntity calendar = new CalendarEntity();
        ReflectionTestUtils.setField(calendar, "id", 1L);
        calendar.setName("Redirected");
        calendar.setRemoteCalendarId("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return calendar;
    }
}
