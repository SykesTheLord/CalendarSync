package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalDavUrisTest {

    @Test
    void resolvesRelativeHrefAgainstBase() throws Exception {
        URI resolved = CalDavUris.resolveWithinSite(
                URI.create("https://dav.example.com/calendars/jacob/"), "/calendars/jacob/work/");
        assertThat(resolved).isEqualTo(URI.create("https://dav.example.com/calendars/jacob/work/"));
    }

    @Test
    void allowsPartitionHostWithinTheSameSite() {
        // iCloud's documented discovery hop: caldav.icloud.com hands every
        // real request off to a numbered partition host.
        assertThatCode(() -> CalDavUris.requireSameSite(
                URI.create("https://caldav.icloud.com/"),
                URI.create("https://p52-caldav.icloud.com/1234567/calendars/")))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesRedirectToAnotherSite() {
        assertThatThrownBy(() -> CalDavUris.requireSameSite(
                URI.create("https://dav.example.com/"),
                URI.create("https://attacker.example.net/collect")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("different site");
    }

    @Test
    void refusesHrefPointingAtAnotherSite() {
        assertThatThrownBy(() -> CalDavUris.resolveWithinSite(
                URI.create("https://dav.example.com/calendars/jacob/"),
                "https://attacker.example.net/calendars/"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("different site");
    }

    @Test
    void refusesHttpsToHttpDowngrade() {
        assertThatThrownBy(() -> CalDavUris.requireSameSite(
                URI.create("https://dav.example.com/"),
                URI.create("http://dav.example.com/")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("downgrade");
    }

    @Test
    void refusesNonHttpScheme() {
        assertThatThrownBy(() -> CalDavUris.resolveWithinSite(
                URI.create("https://dav.example.com/"), "file:///etc/passwd"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("non-HTTP");
    }

    @Test
    void allowsPlainHttpBaseToStayOnHttp() {
        // A LAN CalDAV server on plain http is a supported setup - the rule is
        // "never downgrade", not "https only".
        assertThatCode(() -> CalDavUris.requireSameSite(
                URI.create("http://nas.local:5232/"),
                URI.create("http://nas.local:5232/jacob/calendar/")))
                .doesNotThrowAnyException();
    }
}
