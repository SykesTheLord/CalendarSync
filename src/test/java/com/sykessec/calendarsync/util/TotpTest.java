package com.sykessec.calendarsync.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /**
     * RFC 6238 appendix B's seed for the SHA-1 vectors: the ASCII string
     * "12345678901234567890", i.e. 20 bytes, which is also this app's secret
     * length.
     */
    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.UTF_8);

    /**
     * The RFC's table is published as 8-digit codes; this app emits 6, which is
     * the same value truncated to its last six digits, so the expectations
     * below are the RFC's rows with the leading two digits dropped.
     *
     * This is the whole correctness argument for the implementation. If these
     * pass, the HMAC, the counter packing, the dynamic truncation and the
     * modulus are all right; if any of them is subtly wrong every one of these
     * fails, because each row exercises a different truncation offset.
     */
    @Test
    void matchesTheRfc6238TestVectors() {
        assertThat(codeAt(59L)).isEqualTo("287082");
        assertThat(codeAt(1111111109L)).isEqualTo("081804");
        assertThat(codeAt(1111111111L)).isEqualTo("050471");
        assertThat(codeAt(1234567890L)).isEqualTo("005924");
        assertThat(codeAt(2000000000L)).isEqualTo("279037");
        assertThat(codeAt(20000000000L)).isEqualTo("353130");
    }

    @Test
    void timeStepAdvancesEveryThirtySeconds() {
        assertThat(Totp.timeStep(0L)).isEqualTo(0L);
        assertThat(Totp.timeStep(29L)).isEqualTo(0L);
        assertThat(Totp.timeStep(30L)).isEqualTo(1L);
        assertThat(Totp.timeStep(59L)).isEqualTo(1L);
        // RFC 6238 appendix B: T=59 falls in step 1.
        assertThat(Totp.timeStep(1111111109L)).isEqualTo(37037036L);
    }

    @Test
    void acceptsTheCurrentStepAndOneEitherSide() {
        long now = 1111111111L;
        long step = Totp.timeStep(now);

        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step), now)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step - 1), now)).isEqualTo(step - 1);
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step + 1), now)).isEqualTo(step + 1);
    }

    @Test
    void rejectsAStepOutsideTheSkewWindow() {
        // Two steps out is 60-90 seconds of drift. Accepting it would double
        // the window an attacker gets to guess in, for a clock nobody should be
        // relying on anyway.
        long now = 1111111111L;
        long step = Totp.timeStep(now);

        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step - 2), now)).isNull();
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step + 2), now)).isNull();
    }

    @Test
    void reportsWhichStepMatchedSoTheCallerCanRefuseAReplay() {
        long now = 1111111111L;
        long step = Totp.timeStep(now);

        // The point of returning the step rather than a boolean: the code is
        // valid for 90 seconds, so the caller has to remember it was used.
        Long matched = Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step), now);
        assertThat(matched).isNotNull().isEqualTo(step);
    }

    @Test
    void toleratesTheSpacingAuthenticatorAppsDisplay() {
        long now = 1111111111L;
        String code = Totp.codeAt(RFC_SEED, Totp.timeStep(now));
        String spaced = code.substring(0, 3) + " " + code.substring(3);

        assertThat(Totp.verify(RFC_SEED, spaced, now)).isNotNull();
    }

    @Test
    void rejectsAnythingThatIsNotSixDigits() {
        long now = 1111111111L;

        assertThat(Totp.verify(RFC_SEED, null, now)).isNull();
        assertThat(Totp.verify(RFC_SEED, "", now)).isNull();
        assertThat(Totp.verify(RFC_SEED, "12345", now)).isNull();
        assertThat(Totp.verify(RFC_SEED, "1234567", now)).isNull();
        assertThat(Totp.verify(RFC_SEED, "abcdef", now)).isNull();
    }

    @Test
    void generatesASecretOfTheRightSizeAndEncoding() {
        String secret = Totp.generateSecret();

        assertThat(Base32.decode(secret)).hasSize(Totp.SECRET_BYTES);
        // Unpadded and uppercase, or an authenticator may refuse the URI.
        assertThat(secret).doesNotContain("=").isUpperCase();
        assertThat(Totp.generateSecret()).isNotEqualTo(secret);
    }

    private static String codeAt(long epochSeconds) {
        return Totp.codeAt(RFC_SEED, Totp.timeStep(epochSeconds));
    }
}
