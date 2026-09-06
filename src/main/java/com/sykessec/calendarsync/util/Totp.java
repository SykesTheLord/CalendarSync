package com.sykessec.calendarsync.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP, over RFC 4226 HOTP. Hand-written for the same reason as
 * {@link Base32}: the whole algorithm is one HMAC and a truncation, and adding
 * a dependency to a login path costs more than it saves.
 *
 * THE PARAMETERS BELOW ARE AN INTEROPERABILITY REQUIREMENT, NOT A DEFAULT
 * NOBODY GOT ROUND TO CHANGING. The otpauth:// Key Uri Format defines
 * algorithm, digits and period parameters, and it is tempting to read
 * "algorithm=SHA256" as a free upgrade. It is not: a number of authenticators
 * and password managers ignore that parameter and compute SHA-1 regardless.
 * A server that emits SHA256 and validates SHA256 therefore produces an
 * enrolment that scans perfectly, shows a plausible six-digit code, and never
 * validates - which presents as a clock-skew problem and is close to
 * undebuggable over a support channel. SHA-1 here is not a security claim
 * about SHA-1; HMAC-SHA-1 is not affected by the collision attacks that
 * retired bare SHA-1, and the secret is 160 bits. It is the set every
 * implementation agrees on.
 */
public final class Totp {

    /** RFC 4226 section 4 requires at least 128 bits and recommends 160. */
    public static final int SECRET_BYTES = 20;

    public static final int DIGITS = 6;

    public static final int PERIOD_SECONDS = 30;

    /**
     * Steps either side of "now" that are also accepted, i.e. a +/-30s window.
     * Phone clocks drift, and a user who starts typing at second 29 should not
     * be punished for it. Every extra step widens the guessing target linearly,
     * so this stays at one rather than the three or four some servers allow.
     */
    public static final int SKEW_STEPS = 1;

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int[] POWERS_OF_TEN = { 1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000 };
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A fresh 160-bit shared secret, base32-encoded ready for an otpauth:// URI. */
    public static String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base32.encode(secret);
    }

    /** The time step a given instant falls in. Public because replay protection stores it. */
    public static long timeStep(long epochSeconds) {
        return Math.floorDiv(epochSeconds, PERIOD_SECONDS);
    }

    /**
     * The code for one specific step. Separate from {@link #verify} so tests can
     * drive it with the RFC's own vectors rather than with the current time.
     */
    public static String codeAt(byte[] secret, long step) {
        byte[] message = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (value & 0xff);
            value >>>= 8;
        }

        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            hash = mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }

        // RFC 4226 dynamic truncation: the low nibble of the last byte picks
        // which four bytes of the digest to read, so the code depends on the
        // whole digest rather than a fixed slice of it.
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);

        return padLeft(binary % POWERS_OF_TEN[DIGITS], DIGITS);
    }

    /**
     * Verifies a submitted code against the skew window and reports WHICH step
     * matched, so the caller can reject a step it has already accepted. Returns
     * null when nothing matched.
     *
     * Returning the step rather than a boolean is what makes replay protection
     * possible at all: a code stays valid across three steps, i.e. up to 90
     * seconds, so without recording the accepted step a code seen once - over a
     * shoulder, in a screen share, in a proxy log that captured a POST body -
     * can simply be replayed for the rest of that window.
     */
    public static Long verify(byte[] secret, String submitted, long epochSeconds) {
        String candidate = normalize(submitted);
        if (candidate == null) {
            return null;
        }
        long current = timeStep(epochSeconds);
        Long matched = null;
        for (long step = current - SKEW_STEPS; step <= current + SKEW_STEPS; step++) {
            // No early return: every step in the window is evaluated whether or
            // not an earlier one matched, so the time taken does not reveal how
            // far off the submitted code was.
            if (constantTimeEquals(codeAt(secret, step), candidate) && matched == null) {
                matched = step;
            }
        }
        return matched;
    }

    /**
     * Strips the spaces authenticator apps put in the middle of a code
     * ("123 456") and rejects anything that is not exactly DIGITS digits, so a
     * short or non-numeric entry never reaches the HMAC comparison.
     */
    private static String normalize(String submitted) {
        if (submitted == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(DIGITS);
        for (int i = 0; i < submitted.length(); i++) {
            char c = submitted.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            if (c < '0' || c > '9') {
                return null;
            }
            digits.append(c);
        }
        return digits.length() == DIGITS ? digits.toString() : null;
    }

    /**
     * MessageDigest.isEqual rather than String.equals: isEqual is documented to
     * compare without short-circuiting, so a wrong code costs the same time
     * whether its first digit was wrong or only its last.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String padLeft(int value, int digits) {
        String s = Integer.toString(value);
        if (s.length() >= digits) {
            return s;
        }
        return "0".repeat(digits - s.length()) + s;
    }
}
