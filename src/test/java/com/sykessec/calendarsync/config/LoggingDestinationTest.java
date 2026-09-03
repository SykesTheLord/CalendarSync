package com.sykessec.calendarsync.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import net.logstash.logback.appender.LogstashTcpSocketAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Covers the destination switch in logback-spring.xml. Worth real tests rather
 * than a manual once-over because the switch is done by substituting a property
 * into an {@code <include>} path: a broken branch does not throw, it produces a
 * root logger with no appenders and a line in logback's status manager that
 * nobody is watching. These assertions are what turn that into a test failure.
 *
 * Drives Boot's own {@link LogbackLoggingSystem} rather than booting an
 * application context, since that is the only part of Boot involved - it reads
 * logback-spring.xml, and it is what makes {@code <springProperty/>} resolve at
 * all.
 *
 * The logger context is JVM-global, so each test here leaves the whole suite
 * logging through whatever it just configured; {@link #restoreConsoleLogging()}
 * puts it back.
 */
class LoggingDestinationTest {

    @AfterAll
    static void restoreConsoleLogging() {
        System.clearProperty("LOG_FILE");
        System.clearProperty("LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE");
        configure(Map.of("calendarsync.logging.destination", "console"));
    }

    @Test
    void consoleDestinationAttachesOnlyTheConsoleAppender() {
        configure(Map.of("calendarsync.logging.destination", "console"));

        assertThat(rootAppenders()).singleElement()
                .isInstanceOf(ConsoleAppender.class)
                .extracting(Appender::getName).isEqualTo("CONSOLE");
    }

    @Test
    void destinationDefaultsToConsoleWhenUnset() {
        configure(Map.of());

        assertThat(rootAppenders()).singleElement().isInstanceOf(ConsoleAppender.class);
    }

    @Test
    void fileDestinationRollsToTheConfiguredPath(@TempDir Path tempDir) {
        Path logFile = tempDir.resolve("calendarsync.log");
        configure(Map.of(
                "calendarsync.logging.destination", "file",
                "logging.file.name", logFile.toString(),
                "logging.logback.rollingpolicy.max-file-size", "5MB"));

        assertThat(rootAppenders()).singleElement()
                .isInstanceOfSatisfying(RollingFileAppender.class, appender -> {
                    assertThat(appender.getName()).isEqualTo("FILE");
                    assertThat(appender.getFile()).isEqualTo(logFile.toString());
                    // Proves the appender is still Boot's own, so the rest of the
                    // logging.logback.rollingpolicy.* family keeps working too.
                    assertThat(appender.getRollingPolicy()).isNotNull();
                });
    }

    @Test
    void elasticDestinationShipsOverPlainTcpToTheConfiguredCollector() {
        configure(Map.of(
                "calendarsync.logging.destination", "elastic",
                "calendarsync.logging.elastic.host", "logstash.example",
                "calendarsync.logging.elastic.port", "5555"));

        assertThat(rootAppenders()).singleElement()
                .isInstanceOfSatisfying(LogstashTcpSocketAppender.class, appender -> {
                    assertThat(appender.getName()).isEqualTo("ELASTIC");
                    assertThat(appender.getDestinations())
                            .extracting(InetSocketAddress::getHostString, InetSocketAddress::getPort)
                            .containsExactly(tuple("logstash.example", 5555));
                    assertThat(appender.getSsl())
                            .as("plain TCP while calendarsync.logging.elastic.tls is false")
                            .isNull();
                    assertThat(appender.getEncoder()).isInstanceOf(StructuredLogEncoder.class);
                });
    }

    @Test
    void elasticDestinationEncodesEventsAsOneEcsDocumentPerLine() {
        configure(Map.of(
                "calendarsync.logging.destination", "elastic",
                "spring.application.name", "calendarsync"));

        LogstashTcpSocketAppender appender = (LogstashTcpSocketAppender) rootAppenders().getFirst();
        String encoded = new String(appender.getEncoder().encode(event("sync finished")), StandardCharsets.UTF_8);

        assertThat(encoded)
                .contains("\"ecs\":{\"version\":")
                .contains("\"service\":{\"name\":\"calendarsync\"")
                .contains("\"@timestamp\":")
                .contains("\"message\":\"sync finished\"")
                // A json_lines collector splits the stream on exactly this.
                .endsWith("\n");
    }

    @Test
    void elasticDestinationEnablesTlsWhenAsked() {
        configure(Map.of(
                "calendarsync.logging.destination", "elastic",
                "calendarsync.logging.elastic.tls", "true"));

        LogstashTcpSocketAppender appender = (LogstashTcpSocketAppender) rootAppenders().getFirst();
        assertThat(appender.getSsl()).isNotNull();
    }

    /**
     * The other half of the safety net: logback cannot usefully reject a
     * destination it has never heard of, so the binding has to.
     */
    @Test
    void unknownDestinationFailsBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppPropertiesConfiguration.class)
                .withPropertyValues("calendarsync.logging.destination=syslog")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("calendarsync.logging.destination")
                        .hasStackTraceContaining("must be one of console, file, elastic"));
    }

    /**
     * Relaxed binding would let this through if the property were enum-typed,
     * and logback would then look for a destination-ELASTIC.xml that does not
     * exist.
     */
    @Test
    void upperCaseDestinationFailsBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppPropertiesConfiguration.class)
                .withPropertyValues("calendarsync.logging.destination=ELASTIC")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void outOfRangeElasticPortFailsBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppPropertiesConfiguration.class)
                .withPropertyValues("calendarsync.logging.elastic.port=70000")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("calendarsync.logging.elastic.port"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class AppPropertiesConfiguration {
    }

    private static void configure(Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);

        LogbackLoggingSystem system = new LogbackLoggingSystem(LoggingDestinationTest.class.getClassLoader());
        // initialize() silently returns if the logger context is already marked
        // initialized, which it is from the moment the first test in this class
        // runs - without this every later configure() would be a no-op and the
        // assertions would all be made against the first destination.
        system.cleanUp();

        LogFile logFile = LogFile.get(environment);
        // Boot's LoggingApplicationListener does exactly this before handing the
        // config file over; it is what exports LOG_FILE and the rolling policy
        // settings that destination-file.xml reads.
        system.getSystemProperties(environment).apply(logFile);
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", logFile);
    }

    private static List<Appender<ILoggingEvent>> rootAppenders() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Iterator<Appender<ILoggingEvent>> iterator =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();

        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        iterator.forEachRemaining(appenders::add);
        return appenders;
    }

    /**
     * Built through a real logger rather than field by field, so the event
     * carries the logger context the ECS formatter reaches through for the MDC.
     */
    private static LoggingEvent event(String message) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(LoggingDestinationTest.class);

        return new LoggingEvent(LoggingDestinationTest.class.getName(), logger, Level.INFO, message, null, null);
    }
}
