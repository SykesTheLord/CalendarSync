package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.util.Base32;
import com.sykessec.calendarsync.util.CryptoUtil;
import org.springframework.stereotype.Component;

/**
 * Encrypts app_user.totp_secret, mirroring CredentialCipher exactly but
 * deriving with the "totp" context string instead of "credentials" - a
 * different key from the same CALCLEANER_DB_KEY, which is the same
 * separation-of-protections rule the rest of this application follows.
 *
 * Kept as its own component rather than folded into CredentialCipher with a
 * context parameter: one component per protection domain means the
 * db.encrypted toggle and the context string are visible together in one short
 * file, and a future change to how connection credentials are keyed cannot
 * silently re-key everybody's second factor at the same time.
 *
 * BE AWARE OF WHAT THIS DOES NOT DO. Like CredentialCipher, it is a
 * passthrough when calendarsync.db.encrypted is false - which is the dev
 * profile - so in development the secret sits in the SQLite file in plaintext.
 * That is consistent with how connection credentials already behave and is
 * fine for a dev database, but it means a dev database is not a safe place to
 * enrol an account you actually care about. In prod the column is encrypted
 * here AND the whole file is encrypted by the Willena driver; this layer is
 * what protects the secret if a decrypted copy of the database ever escapes -
 * a hot backup, a support export, a developer's laptop.
 */
@Component
public class TotpSecretCipher {

    private final AppProperties appProperties;

    public TotpSecretCipher(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** Takes the base32 form shown to the user and returns what goes in the column. */
    public byte[] encode(String base32Secret) {
        if (base32Secret == null) {
            return null;
        }
        if (!appProperties.getDb().isEncrypted()) {
            return Base32.decode(base32Secret);
        }
        return CryptoUtil.encrypt(base32Secret, keyBytes());
    }

    /** Returns the raw secret bytes the HMAC needs, or null if the user has none. */
    public byte[] decodeToRawSecret(byte[] stored) {
        if (stored == null) {
            return null;
        }
        if (!appProperties.getDb().isEncrypted()) {
            return stored;
        }
        return Base32.decode(CryptoUtil.decrypt(stored, keyBytes()));
    }

    private byte[] keyBytes() {
        String envKey = System.getenv("CALCLEANER_DB_KEY");
        if (envKey == null || envKey.isBlank()) {
            throw new IllegalStateException(
                    "calendarsync.db.encrypted=true but CALCLEANER_DB_KEY is not set");
        }
        return CryptoUtil.deriveKey(envKey, "totp");
    }
}
