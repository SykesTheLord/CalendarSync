package com.sykessec.calendarsync.security;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * The only thing that survives between the password step and the code step.
 *
 * It is deliberately NOT an Authentication, and it is deliberately the sole
 * occupant of a brand new session: the user has proved one factor, which in
 * this design buys them nothing except the right to be asked for the second.
 * Spring Security is not told about them at all until the code verifies, so
 * there is no window in which a partially authenticated principal exists for
 * some other filter or view to misread as a real one.
 *
 * targetUrl is carried here because the saved request died with the session
 * that was invalidated between the two steps - without it, someone who
 * followed a deep link into the app would land on the default page instead of
 * where they were going.
 */
public record PendingSecondFactor(Long userId, String username, Instant expiresAt, String targetUrl)
        implements Serializable {

    /** Session attribute name. Held in the servlet session, not the Vaadin one. */
    public static final String SESSION_ATTRIBUTE = "CALENDARSYNC_PENDING_SECOND_FACTOR";

    /**
     * Long enough to find a phone, short enough that an unattended browser on a
     * half-finished login is not a standing invitation.
     */
    public static final Duration LIFETIME = Duration.ofMinutes(5);

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public static PendingSecondFactor forUser(Long userId, String username, String targetUrl) {
        return new PendingSecondFactor(userId, username, Instant.now().plus(LIFETIME), targetUrl);
    }
}
