package com.sykessec.calendarsync.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoUtilTest {

    @Test
    void roundTripsPlaintext() {
        byte[] key = CryptoUtil.deriveKey("super-secret-env-value", "credentials");
        byte[] encrypted = CryptoUtil.encrypt("refresh-token-abc123", key);

        assertThat(encrypted).isNotEmpty();
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("refresh-token-abc123");

        assertThat(CryptoUtil.decrypt(encrypted, key)).isEqualTo("refresh-token-abc123");
    }

    @Test
    void sameKeyDifferentIvProducesDifferentCiphertext() {
        byte[] key = CryptoUtil.deriveKey("secret", "credentials");
        byte[] a = CryptoUtil.encrypt("value", key);
        byte[] b = CryptoUtil.encrypt("value", key);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void wrongKeyFailsToDecrypt() {
        byte[] key = CryptoUtil.deriveKey("secret-1", "credentials");
        byte[] wrongKey = CryptoUtil.deriveKey("secret-2", "credentials");
        byte[] encrypted = CryptoUtil.encrypt("value", key);

        assertThatThrownBy(() -> CryptoUtil.decrypt(encrypted, wrongKey))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deriveKeyIsDeterministicAndContextScoped() {
        byte[] a = CryptoUtil.deriveKey("secret", "credentials");
        byte[] b = CryptoUtil.deriveKey("secret", "credentials");
        byte[] c = CryptoUtil.deriveKey("secret", "whole-db");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }
}
