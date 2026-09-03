package com.sykessec.calendarsync;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every SQLite-backed test gets a fresh, Flyway-migrated file rather than
 * sharing the dev database - shared by CalendarSyncApplicationTests and
 * UserScopingIsolationTest, and every further integration test added in
 * later stages.
 *
 * Deliberately NOT a JUnit @TempDir: Spring reuses one cached
 * ApplicationContext across test classes whose @DynamicPropertySource method
 * is inherited from this shared base (the customizer's identity, not the
 * property VALUE, drives the cache key) - a per-test-class @TempDir gets
 * deleted between classes while the cached context (and its already-open
 * DataSource pointed at that now-missing file) lives on, so the second test
 * class silently gets a fresh, unmigrated SQLite file. One directory for the
 * whole JVM run, cleaned up at shutdown, avoids that mismatch entirely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
public abstract class AbstractIntegrationTest {

    private static final Path TEMP_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            Path dir = Files.createTempDirectory("calendarsync-test-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void overrideDbPath(DynamicPropertyRegistry registry) {
        registry.add("calendarsync.db.path", () -> TEMP_DIR.resolve("test.db").toString());
    }
}
