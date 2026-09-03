package com.sykessec.calendarsync.provider.caldav;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Pure functions: builds the handful of fixed-shape PROPFIND/REPORT request
 * bodies this app ever sends, and parses the corresponding multistatus XML
 * responses. No data-binding library - the request/response shapes are
 * small and fixed enough that DOM + XPath is simpler than mapping classes.
 */
public final class CalDavXmlSupport {

    private static final String DAV_NS = "DAV:";
    private static final String CALDAV_NS = "urn:ietf:params:xml:ns:caldav";

    private CalDavXmlSupport() {
    }

    // --- Request bodies ---

    public static String currentUserPrincipalRequest() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:current-user-principal/>
                  </D:prop>
                </D:propfind>
                """;
    }

    public static String calendarHomeSetRequest() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop>
                    <C:calendar-home-set/>
                  </D:prop>
                </D:propfind>
                """;
    }

    public static String calendarCollectionListRequest() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop>
                    <D:resourcetype/>
                    <D:displayname/>
                    <D:getctag xmlns:CS="http://calendarserver.org/ns/"/>
                  </D:prop>
                </D:propfind>
                """;
    }

    public static String vEventQueryRequest() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:prop>
                    <D:getetag/>
                    <C:calendar-data/>
                  </D:prop>
                  <C:filter>
                    <C:comp-filter name="VCALENDAR">
                      <C:comp-filter name="VEVENT"/>
                    </C:comp-filter>
                  </C:filter>
                </C:calendar-query>
                """;
    }

    // --- Response parsing ---

    public record HrefAndEtag(String href, String etag, String calendarData) {
    }

    public record DiscoveredCalendar(String href, String displayName) {
    }

    public static Optional<String> parseCurrentUserPrincipal(String xml) {
        return firstTextAtXPath(xml, "//D:current-user-principal/D:href");
    }

    public static Optional<String> parseCalendarHomeSet(String xml) {
        return firstTextAtXPath(xml, "//C:calendar-home-set/D:href");
    }

    /** Depth:1 PROPFIND on the home-set - one entry per child collection, filtered to actual calendars. */
    public static List<DiscoveredCalendar> parseCalendarCollections(String xml) {
        List<DiscoveredCalendar> result = new ArrayList<>();
        Document doc = parse(xml);
        XPath xpath = newXPath();
        try {
            NodeList responses = (NodeList) xpath.evaluate("//D:response", doc, XPathConstants.NODESET);
            for (int i = 0; i < responses.getLength(); i++) {
                Node response = responses.item(i);
                boolean isCalendar = ((Boolean) xpath.evaluate(".//D:resourcetype/C:calendar", response,
                        XPathConstants.BOOLEAN));
                if (!isCalendar) {
                    continue;
                }
                String href = textOrNull((Node) xpath.evaluate("D:href", response, XPathConstants.NODE));
                String displayName = textOrNull((Node) xpath.evaluate(".//D:displayname", response, XPathConstants.NODE));
                if (href != null) {
                    result.add(new DiscoveredCalendar(href, displayName == null ? href : displayName));
                }
            }
        } catch (javax.xml.xpath.XPathExpressionException e) {
            throw new IllegalArgumentException("Malformed CalDAV multistatus response", e);
        }
        return result;
    }

    /** REPORT calendar-query response - one entry per VEVENT resource found. */
    public static List<HrefAndEtag> parseCalendarQueryResponse(String xml) {
        List<HrefAndEtag> result = new ArrayList<>();
        Document doc = parse(xml);
        XPath xpath = newXPath();
        try {
            NodeList responses = (NodeList) xpath.evaluate("//D:response", doc, XPathConstants.NODESET);
            for (int i = 0; i < responses.getLength(); i++) {
                Node response = responses.item(i);
                String href = textOrNull((Node) xpath.evaluate("D:href", response, XPathConstants.NODE));
                String etag = textOrNull((Node) xpath.evaluate(".//D:getetag", response, XPathConstants.NODE));
                String calendarData = textOrNull((Node) xpath.evaluate(".//C:calendar-data", response, XPathConstants.NODE));
                if (href != null && calendarData != null) {
                    result.add(new HrefAndEtag(href, etag, calendarData));
                }
            }
        } catch (javax.xml.xpath.XPathExpressionException e) {
            throw new IllegalArgumentException("Malformed CalDAV multistatus response", e);
        }
        return result;
    }

    // --- helpers ---

    private static Optional<String> firstTextAtXPath(String xml, String expression) {
        Document doc = parse(xml);
        XPath xpath = newXPath();
        try {
            Node node = (Node) xpath.evaluate(expression, doc, XPathConstants.NODE);
            return Optional.ofNullable(textOrNull(node));
        } catch (javax.xml.xpath.XPathExpressionException e) {
            throw new IllegalArgumentException("Malformed CalDAV multistatus response", e);
        }
    }

    private static String textOrNull(Node node) {
        if (node == null) {
            return null;
        }
        String text = node.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse CalDAV XML response", e);
        }
    }

    private static XPath newXPath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return switch (prefix) {
                    case "D" -> DAV_NS;
                    case "C" -> CALDAV_NS;
                    default -> null;
                };
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return null;
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                return null;
            }
        });
        return xpath;
    }
}
