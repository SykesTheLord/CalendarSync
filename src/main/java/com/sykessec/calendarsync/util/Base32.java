package com.sykessec.calendarsync.util;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * RFC 4648 base32, which is the only encoding an authenticator app will accept
 * for a TOTP shared secret - the otpauth:// URI's secret= parameter and the
 * "enter this key manually" string are both base32, never hex and never base64.
 *
 * Hand-written rather than taken from commons-codec: commons-codec IS on the
 * classpath, but only transitively (via the Google HTTP client), and this is
 * the encoding of a credential. A dependency that nothing declares can be
 * dropped by an unrelated upgrade of the library that happened to pull it in,
 * and the failure would land here, on the path that decides whether people can
 * log in. Forty lines of table lookup is cheaper than that risk.
 *
 * The asymmetry between encode and decode is deliberate. Encoding is strict -
 * uppercase and unpadded, because that is what every authenticator expects and
 * because '=' in a URI query parameter is an avoidable interoperability
 * argument. Decoding is lenient - lowercase, spaces and padding are all
 * accepted, because the manual-entry path has a human retyping what the screen
 * showed them, usually in the groups of four that this app displays.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Reverse lookup, indexed by ASCII value; -1 means "not a base32 digit". */
    private static final int[] DECODE = new int[128];

    static {
        Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE[ALPHABET.charAt(i)] = i;
            DECODE[Character.toLowerCase(ALPHABET.charAt(i))] = i;
        }
    }

    private Base32() {
    }

    /** Uppercase and unpadded - the form that goes into an otpauth:// URI. */
    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(ALPHABET.charAt((buffer >> bitsLeft) & 0x1f));
            }
        }
        // A trailing partial group is left-aligned and zero-filled, per RFC 4648.
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    /**
     * Tolerant of lowercase, whitespace and '=' padding, because this is what a
     * user typed. Anything else is rejected rather than silently skipped: a
     * mistyped secret must fail loudly at enrolment, not decode to something
     * subtly different and produce codes that never match.
     */
    public static byte[] decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("No secret supplied");
        }
        int buffer = 0;
        int bitsLeft = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length() * 5 / 8 + 1);
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '=' || c == ' ' || c == '-' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            int value = c < DECODE.length ? DECODE[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("Not a valid base32 character: '" + c + "'");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
