package com.sykessec.calendarsync.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for calendar_connection.encrypted_credentials in the prod
 * profile. This is a SEPARATE protection from the Willena whole-database
 * encryption (DataSourceConfig) - that key protects the DB file at rest
 * (e.g. a stolen disk), keyed the same way for every row; this one encrypts
 * one specific column's plaintext before Hibernate ever writes it, so the
 * credential is never in cleartext even in a decrypted-DB-file scenario
 * (a hot backup, a misconfigured dev copy, etc). Don't conflate the two.
 */
public final class CryptoUtil {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    public static byte[] encrypt(String plaintext, byte[] key256) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key256, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Store as iv || ciphertext (GCM tag is already appended to ciphertext by the JCE).
            return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public static String decrypt(byte[] stored, byte[] key256) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            byte[] iv = new byte[GCM_IV_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key256, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential - wrong key, or data is not encrypted", e);
        }
    }

    /** Derives a 256-bit key from an arbitrary-length secret via SHA-256 - simple and sufficient for a single-instance app. */
    public static byte[] deriveKey(String secret, String context) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(context.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }

    public static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] fromBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
