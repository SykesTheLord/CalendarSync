package com.sykessec.calendarsync.util;

import java.nio.charset.StandardCharsets;

/**
 * Builds the otpauth:// URI that goes into the enrolment QR code, per the Key
 * Uri Format that every authenticator and password manager parses.
 *
 * Two details here are the difference between "imports as CalendarSync" and
 * "imports as an unnamed entry the user cannot find again", and both are easy
 * to get wrong in a way no unit test catches unless it is written for it.
 *
 * FIRST: the issuer is emitted TWICE - once as the "Issuer:Account" label
 * prefix, and once as the issuer= parameter. The spec calls the parameter
 * strongly recommended and the prefix the legacy form, but readers disagree
 * about which wins, and some older ones only read the prefix. Emitting both,
 * with identical values, is what the spec tells implementers to do and is what
 * makes Bitwarden and 1Password show a named entry.
 *
 * SECOND: the label is a URI PATH segment, so it needs path escaping, and
 * java.net.URLEncoder does not do that - it does HTML form encoding, whose one
 * famous difference is that a space becomes '+' rather than "%20". A username
 * with a space in it would import with a literal plus sign in its name. The
 * escaping below is therefore done by hand against RFC 3986's unreserved set.
 */
public final class TotpUri {

    /** Shown as the account name in the authenticator, above the code. */
    public static final String ISSUER = "CalendarSync";

    private static final String UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private TotpUri() {
    }

    public static String build(String username, String base32Secret) {
        String label = escape(ISSUER) + "%3A" + escape(username);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + escape(ISSUER)
                + "&algorithm=SHA1"
                + "&digits=" + Totp.DIGITS
                + "&period=" + Totp.PERIOD_SECONDS;
    }

    /**
     * Groups the secret in fours for the "type it in by hand" path. Desktop
     * password-manager users routinely paste rather than scan, and an unbroken
     * 32-character string is materially harder to transcribe correctly.
     */
    public static String formatSecretForDisplay(String base32Secret) {
        StringBuilder out = new StringBuilder(base32Secret.length() + base32Secret.length() / 4);
        for (int i = 0; i < base32Secret.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(base32Secret.charAt(i));
        }
        return out.toString();
    }

    /** Percent-encoding for a URI path segment: everything outside RFC 3986's unreserved set. */
    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (UNRESERVED.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }
}
