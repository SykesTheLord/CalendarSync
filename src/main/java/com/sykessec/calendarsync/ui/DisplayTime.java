package com.sykessec.calendarsync.ui;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Renders the TEXT timestamps SqliteTimestamps writes ("yyyy-MM-dd HH:mm:ss",
 * always UTC) for a human.
 *
 * The grids used to print the stored string verbatim, which meant a UTC
 * instant shown with no marker saying so - "21:04" for something that
 * happened at 23:04 where the user was sitting. In a calendar application
 * that is not a cosmetic problem, so this converts into the zone the
 * application is running in and names the zone in the output rather than
 * leaving the reader to guess which one they are looking at.
 */
public final class DisplayTime {

    /** Must match SqliteTimestamps.FORMAT - this parses what that writes. */
    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z");

    private DisplayTime() {
    }

    /**
     * Returns "" for a null/blank timestamp (a not-yet-generated feed, a
     * never-restored audit row) and the raw string for anything that doesn't
     * parse - showing something odd beats swallowing a row into a stack trace
     * inside a grid renderer.
     */
    public static String format(String storedUtc) {
        if (storedUtc == null || storedUtc.isBlank()) {
            return "";
        }
        try {
            return LocalDateTime.parse(storedUtc.trim(), STORED)
                    .atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(DISPLAY);
        } catch (DateTimeParseException e) {
            return storedUtc;
        }
    }
}
