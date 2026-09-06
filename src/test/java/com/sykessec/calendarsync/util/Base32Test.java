package com.sykessec.calendarsync.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base32Test {

    /**
     * RFC 4648 section 10's own vectors. Encoding is unpadded here, so the
     * expected values are the RFC's with the '=' run removed.
     */
    @Test
    void matchesTheRfc4648TestVectors() {
        assertThat(encode("")).isEmpty();
        assertThat(encode("f")).isEqualTo("MY");
        assertThat(encode("fo")).isEqualTo("MZXQ");
        assertThat(encode("foo")).isEqualTo("MZXW6");
        assertThat(encode("foob")).isEqualTo("MZXW6YQ");
        assertThat(encode("fooba")).isEqualTo("MZXW6YTB");
        assertThat(encode("foobar")).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void decodesTheRfc4648TestVectorsBackAgain() {
        assertThat(decode("MY")).isEqualTo("f");
        assertThat(decode("MZXQ")).isEqualTo("fo");
        assertThat(decode("MZXW6")).isEqualTo("foo");
        assertThat(decode("MZXW6YQ")).isEqualTo("foob");
        assertThat(decode("MZXW6YTB")).isEqualTo("fooba");
        assertThat(decode("MZXW6YTBOI")).isEqualTo("foobar");
    }

    @Test
    void roundTripsArbitraryBytes() {
        byte[] secret = new byte[Totp.SECRET_BYTES];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i * 7 + 3);
        }
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
    }

    @Test
    void acceptsWhatAPersonActuallyTypes() {
        // The enrolment screen shows the secret uppercase and grouped in fours;
        // a user retyping it reproduces the spaces, and some clients paste back
        // the padded lowercase form. All three must mean the same secret.
        String canonical = "MZXW6YTBOI";
        assertThat(decode("mzxw6ytboi")).isEqualTo("foobar");
        assertThat(decode("MZXW 6YTB OI")).isEqualTo("foobar");
        assertThat(decode("MZXW6YTBOI======")).isEqualTo("foobar");
        assertThat(Base32.decode(canonical)).isEqualTo(Base32.decode("mzxw 6ytb oi=="));
    }

    @Test
    void rejectsACharacterThatIsNotBase32() {
        // '1' and '0' are deliberately absent from the alphabet because they
        // are the classic misreadings of 'I' and 'O'. A mistyped secret has to
        // fail at enrolment rather than decode to something subtly different
        // and produce codes that never match.
        assertThatThrownBy(() -> Base32.decode("MZXW6YTB01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid base32");
    }

    private static String encode(String plain) {
        return Base32.encode(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String encoded) {
        return new String(Base32.decode(encoded), StandardCharsets.UTF_8);
    }
}
