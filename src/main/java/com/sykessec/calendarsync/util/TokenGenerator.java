package com.sykessec.calendarsync.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * SecureRandom-based tokens for anything that acts as a bearer credential:
 * published_feed.access_token, and the one-time bootstrap admin password.
 * Never sequential, never predictable.
 */
public final class TokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenGenerator() {
    }

    /** URL-safe, unpadded - suitable for use directly in a /feed/{token}.ics path. */
    public static String urlSafeToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A human-typeable one-time password for the bootstrap admin account. */
    public static String humanReadablePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * One TOTP recovery code, formatted "xxxxx-xxxxx".
     *
     * Ten characters from a 32-character alphabet is 50 bits, which is what
     * lets these be stored as a plain SHA-256 rather than a slow hash - there
     * is no dictionary to try. The alphabet excludes the characters people
     * confuse when copying from a screen to paper and back (0/O, 1/I/l), since
     * that is exactly the journey a recovery code makes, often months before
     * it is used.
     */
    public static String recoveryCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
