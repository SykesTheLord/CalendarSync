package com.sykessec.calendarsync.config;

import ch.qos.logback.core.net.ssl.SSLConfiguration;
import net.logstash.logback.appender.LogstashTcpSocketAppender;

/**
 * The "elastic" logging destination's appender: a plain
 * {@link LogstashTcpSocketAppender} plus a {@code tls} boolean.
 *
 * The boolean is the entire reason this class exists. Logback turns TLS on for
 * the presence of an {@code <ssl>} element, not for a value, and it has no way
 * to make an element conditional - {@code <if>} needs Janino on the classpath,
 * and a nested {@code <include>} is not supported at all (logback reports
 * "Ignoring unknown property [include]" to its status manager and carries on in
 * plaintext, which is the worst possible failure mode for a transport-security
 * switch).
 * Both alternatives - dragging in Janino, or duplicating the whole appender
 * definition into a tls-on and a tls-off copy that then have to be kept in
 * step - cost more than this subclass does.
 *
 * Configured from
 * {@code src/main/resources/com/sykessec/calendarsync/logback/destination-elastic.xml};
 * nothing constructs it from Java.
 */
public class ElasticTcpAppender extends LogstashTcpSocketAppender {

    /**
     * Passing false must leave the SSL configuration null rather than an empty
     * one: null is exactly what the superclass checks to decide between
     * SocketFactory.getDefault() and an SSL socket factory.
     */
    public void setTls(boolean tls) {
        setSsl(tls ? new SSLConfiguration() : null);
    }
}
