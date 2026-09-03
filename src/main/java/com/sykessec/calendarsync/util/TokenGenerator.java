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
}
