package com.sykessec.calendarsync.provider.caldav;

import com.sun.net.httpserver.HttpServer;
import com.sykessec.calendarsync.provider.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CalDavClient follows redirects itself, and only within the site the user
 * pointed the connection at.
 *
 * Both halves matter and they pull against each other. Nothing underneath
 * follows redirects - Spring's RestClient resolves to a reactor-netty client
 * defaulting to followRedirect(false) - so before this, a server that
 * redirected a collection URL broke listing, delete and restore outright, and
 * only discovery's PROPFIND worked because it had its own hand-rolled loop.
 * But this client attaches the user's CalDAV password to every request, so
 * simply switching the HTTP client's redirect following on would hand that
 * password to whatever host a hostile or compromised server named.
 *
 * So the tests below assert the credential DOES ride along on a same-site hop
 * (or the follow is useless) and that a cross-site hop is refused before any
 * request is made (or the follow is dangerous).
 */
class CalDavClientRedirectTest {

    private static final CalDavCredentials CREDENTIALS = new CalDavCredentials("alice", "app-specific-password");

    private static final String MULTISTATUS = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/calendars/alice/</d:href></d:response></d:multistatus>
            """;

    private HttpServer server;
    private final List<String> authHeadersSeen = new ArrayList<>();
    private final List<String> methodsSeen = new ArrayList<>();
    private CalDavClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/moved", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", "/calendars/alice/");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/offsite", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", "https://calendar-thief.example.com/collect");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/calendars/alice/", exchange -> {
            record(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = MULTISTATUS.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(207, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new CalDavClient(RestClient.builder());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void followsASameSiteRedirectAndCarriesTheCredential() throws Exception {
        CalDavResponse response = client.propfind(uri("/moved"), 0, "<propfind/>", CREDENTIALS);

        assertThat(response.status()).isEqualTo(207);
        assertThat(response.body()).contains("/calendars/alice/");
        // Both hops, and the second one is the point: a followed request that
        // dropped the auth header would just 401 on every real server.
        assertThat(authHeadersSeen).hasSize(2).allSatisfy(header ->
                assertThat(header).isNotNull().startsWith("Basic "));
    }

    /**
     * The method survives the hop. RFC 9110 asks a non-browser client not to
     * rewrite it, and a DELETE quietly re-issued as a GET would report success
     * for a deletion that never happened.
     */
    @Test
    void keepsTheRequestMethodAcrossTheRedirect() throws Exception {
        client.propfind(uri("/moved"), 0, "<propfind/>", CREDENTIALS);

        assertThat(methodsSeen).containsExactly("PROPFIND", "PROPFIND");
    }

    /**
     * finalUri is what discovery resolves hrefs against. Resolving them against
     * the URI that redirected would point at the wrong collection, and iCloud's
     * hop to a numbered partition host makes that the normal case.
     */
    @Test
    void reportsTheUriTheResponseActuallyCameFrom() throws Exception {
        CalDavResponse response = client.propfind(uri("/moved"), 0, "<propfind/>", CREDENTIALS);

        assertThat(response.finalUri()).isEqualTo(uri("/calendars/alice/"));
    }

    @Test
    void refusesToCarryTheCredentialToAnotherSite() {
        assertThatThrownBy(() -> client.propfind(uri("/offsite"), 0, "<propfind/>", CREDENTIALS))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("different site");

        // Only the original request happened: the hop was refused before the
        // password could be sent anywhere else.
        assertThat(authHeadersSeen).hasSize(1);
    }

    @Test
    void givesUpOnARedirectLoopInsteadOfSpinning() {
        assertThatThrownBy(() -> client.propfind(uri("/loop"), 0, "<propfind/>", CREDENTIALS))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("redirected more than");
    }

    private synchronized void record(String method, String authorization) {
        methodsSeen.add(method);
        authHeadersSeen.add(authorization);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
