package com.sykessec.calendarsync.provider.caldav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against captured/fixture multistatus XML strings, not a live server - see
 * the plan's testing strategy: no live-network tests in the automated suite.
 */
class CalDavXmlSupportTest {

    @Test
    void parsesCurrentUserPrincipalHref() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:current-user-principal>
                          <D:href>/123456789/principal/</D:href>
                        </D:current-user-principal>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
                """;

        assertThat(CalDavXmlSupport.parseCurrentUserPrincipal(xml))
                .contains("/123456789/principal/");
    }

    @Test
    void parsesCalendarHomeSetHref() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response>
                    <D:href>/123456789/principal/</D:href>
                    <D:propstat>
                      <D:prop>
                        <C:calendar-home-set>
                          <D:href>https://p36-caldav.icloud.com/123456789/calendars/</D:href>
                        </C:calendar-home-set>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
                """;

        assertThat(CalDavXmlSupport.parseCalendarHomeSet(xml))
                .contains("https://p36-caldav.icloud.com/123456789/calendars/");
    }

    @Test
    void parsesCalendarCollectionsAndSkipsNonCalendarChildren() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response>
                    <D:href>/123456789/calendars/home/</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                        <D:displayname>Home</D:displayname>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/123456789/calendars/work/</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                        <D:displayname>Work</D:displayname>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/123456789/calendars/</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:resourcetype><D:collection/></D:resourcetype>
                        <D:displayname>calendars</D:displayname>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
                """;

        List<CalDavXmlSupport.DiscoveredCalendar> calendars = CalDavXmlSupport.parseCalendarCollections(xml);

        assertThat(calendars).extracting(CalDavXmlSupport.DiscoveredCalendar::displayName)
                .containsExactlyInAnyOrder("Home", "Work");
        assertThat(calendars).extracting(CalDavXmlSupport.DiscoveredCalendar::href)
                .containsExactlyInAnyOrder("/123456789/calendars/home/", "/123456789/calendars/work/");
    }

    @Test
    void parsesVEventQueryResponseWithEtagAndCalendarData() {
        String ics = "BEGIN:VCALENDAR\\nVERSION:2.0\\nBEGIN:VEVENT\\nUID:evt-1\\nSUMMARY:Standup\\nEND:VEVENT\\nEND:VCALENDAR";
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:response>
                    <D:href>/123456789/calendars/home/evt-1.ics</D:href>
                    <D:propstat>
                      <D:prop>
                        <D:getetag>"abc123"</D:getetag>
                        <C:calendar-data>%s</C:calendar-data>
                      </D:prop>
                      <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                  </D:response>
                </D:multistatus>
                """.formatted(ics);

        List<CalDavXmlSupport.HrefAndEtag> results = CalDavXmlSupport.parseCalendarQueryResponse(xml);

        assertThat(results).hasSize(1);
        CalDavXmlSupport.HrefAndEtag result = results.get(0);
        assertThat(result.href()).isEqualTo("/123456789/calendars/home/evt-1.ics");
        assertThat(result.etag()).isEqualTo("\"abc123\"");
        assertThat(result.calendarData()).contains("UID:evt-1");
    }

    @Test
    void emptyMultistatusYieldsEmptyList() {
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:"/>
                """;
        assertThat(CalDavXmlSupport.parseCalendarQueryResponse(xml)).isEmpty();
        assertThat(CalDavXmlSupport.parseCalendarCollections(xml)).isEmpty();
    }
}
