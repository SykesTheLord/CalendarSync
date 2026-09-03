package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.util.CryptoUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * The single place that decides whether calendar_connection.encrypted_credentials
 * is actually encrypted - a config toggle (calendarsync.db.encrypted, the
 * same flag DataSourceConfig uses), not a code fork. Every write goes
 * through encode(), every provider read goes through decode(), so the
 * on/off switch lives in exactly one place.
 */
@Component
public class CredentialCipher {

    private final AppProperties appProperties;

    public CredentialCipher(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public byte[] encode(String raw) {
        if (raw == null) {
            return null;
        }
        if (!appProperties.getDb().isEncrypted()) {
            return raw.getBytes(StandardCharsets.UTF_8);
        }
        return CryptoUtil.encrypt(raw, keyBytes());
    }

    public String decode(byte[] stored) {
        if (stored == null) {
            return null;
        }
        if (!appProperties.getDb().isEncrypted()) {
            return new String(stored, StandardCharsets.UTF_8);
        }
        return CryptoUtil.decrypt(stored, keyBytes());
    }

    /**
     * Derived from the same CALCLEANER_DB_KEY as the whole-database
     * encryption, but with a distinct "credentials" context string, so it's
     * a different key even though it comes from the same secret - not the
     * same protection reused, per the spec's "don't conflate the two" note.
     */
    private byte[] keyBytes() {
        String envKey = System.getenv("CALCLEANER_DB_KEY");
        if (envKey == null || envKey.isBlank()) {
            throw new IllegalStateException(
                    "calendarsync.db.encrypted=true but CALCLEANER_DB_KEY is not set");
        }
        return CryptoUtil.deriveKey(envKey, "credentials");
    }
}
