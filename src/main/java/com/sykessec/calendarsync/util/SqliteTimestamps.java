package com.sykessec.calendarsync.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Entities set their own TEXT timestamp columns via @PrePersist rather than
 * relying on SQLite's {@code DEFAULT (datetime('now'))} clause, because
 * Hibernate always includes a mapped column in the INSERT statement (even as
 * NULL), which overrides a DB-side DEFAULT entirely. This formats
 * consistently with SQLite's own datetime('now') output.
 */
public final class SqliteTimestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private SqliteTimestamps() {
    }

    public static String now() {
        return FORMAT.format(Instant.now());
    }
}
