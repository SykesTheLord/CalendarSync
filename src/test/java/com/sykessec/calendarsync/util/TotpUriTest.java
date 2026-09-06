package com.sykessec.calendarsync.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpUriTest {

    @Test
    void carriesTheIssuerInBothPlacesReadersLookFor() {
        String uri = TotpUri.build("alice", "MZXW6YTBOI");

        // The label prefix is the legacy form and the parameter is the current
        // one; readers disagree about which wins, so both are emitted with the
        // same value. Dropping either is what produces an unnamed entry in
        // someone's password manager.
        assertThat(uri).startsWith("otpauth://totp/CalendarSync%3Aalice?");
        assertThat(uri).contains("&issuer=CalendarSync");
    }

    @Test
    void statesTheAlgorithmDigitsAndPeriodExplicitly() {
        String uri = TotpUri.build("alice", "MZXW6YTBOI");

        assertThat(uri).contains("secret=MZXW6YTBOI");
        assertThat(uri).contains("algorithm=SHA1");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
    }

    @Test
    void percentEncodesASpaceRatherThanTurningItIntoAPlus() {
        // The trap this test exists for: java.net.URLEncoder does HTML form
        // encoding, where a space becomes '+'. In a URI path segment that is a
        // literal plus sign, so "ada lovelace" would import under the name
        // "ada+lovelace" and nobody would know why.
        String uri = TotpUri.build("ada lovelace", "MZXW6YTBOI");

        assertThat(uri).contains("CalendarSync%3Aada%20lovelace");
        assertThat(uri).doesNotContain("+");
    }

    @Test
    void escapesACharacterThatWouldOtherwiseSplitTheLabel() {
        // A colon in the username would look like a second Issuer:Account
        // separator and silently truncate the displayed name.
        String uri = TotpUri.build("host:alice", "MZXW6YTBOI");

        assertThat(uri).contains("CalendarSync%3Ahost%3Aalice");
    }

    @Test
    void escapesNonAsciiAsUtf8() {
        String uri = TotpUri.build("josé", "MZXW6YTBOI");

        // U+00E9 is 0xC3 0xA9 in UTF-8.
        assertThat(uri).contains("jos%C3%A9");
    }

    @Test
    void groupsTheSecretInFoursForManualEntry() {
        assertThat(TotpUri.formatSecretForDisplay("MZXW6YTBOIABCDEF"))
                .isEqualTo("MZXW 6YTB OIAB CDEF");
    }
}
