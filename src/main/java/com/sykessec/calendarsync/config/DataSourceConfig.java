package com.sykessec.calendarsync.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Single property-driven DataSource bean, not a code fork per driver: which
 * driver JAR is actually on the classpath is decided at build time by the
 * Maven "dev"/"prod" profile (see pom.xml), and this class only decides the
 * JDBC URL shape based on calendarsync.db.encrypted. Both the Xerial and
 * Willena drivers register the same "org.sqlite.JDBC" class and accept the
 * same "jdbc:sqlite:" URL prefix.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(AppProperties props) {
        File dbFile = new File(props.getDb().getPath());
        File parent = dbFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        String url = "jdbc:sqlite:" + dbFile.getPath();
        if (props.getDb().isEncrypted()) {
            String key = System.getenv("CALCLEANER_DB_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                        "calendarsync.db.encrypted=true but CALCLEANER_DB_KEY is not set");
            }
            // Willena/SQLite3MultipleCiphers key parameter - re-verify the exact
            // parameter name against current Willena docs when Stage 4 wires
            // this up fully; this is a Stage 0 placeholder, not the final
            // production-hardened encryption path.
            //
            // Percent-encoded: an unencoded key containing '&', '#' or '%'
            // would silently truncate the parameter or corrupt the URL, and
            // the failure mode is "database won't open" long after the key
            // was chosen.
            url = url + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        }

        HikariDataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url(url)
                .type(HikariDataSource.class)
                .build();

        // SQLite ignores every ON DELETE CASCADE / SET NULL in V1__initial_schema.sql
        // unless foreign key enforcement is switched on, and it defaults to OFF -
        // per connection, so it has to be pool init SQL rather than a one-off.
        // Without it, deleting a calendar_connection silently orphans its calendar,
        // sync_state and published_feed_source rows instead of cascading to them.
        dataSource.setConnectionInitSql("PRAGMA foreign_keys = ON");
        return dataSource;
    }
}
