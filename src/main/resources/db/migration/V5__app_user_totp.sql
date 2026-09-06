-- Two-factor authentication (TOTP, RFC 6238) for app_user.
--
-- As in V3, plain ADD COLUMN is enough: SQLite permits a NOT NULL column on
-- ADD COLUMN as long as a constant default is supplied, so none of this needs
-- the rebuild-and-copy that V2 and V4 required. Checked in that order against
-- a scratch database before this file was written.
--
-- Every default reproduces the behaviour that existed before this migration -
-- two-factor off for everyone, nothing required, no secret. An upgraded
-- install therefore logs in exactly as it did yesterday until somebody
-- deliberately enrols, which is what an operator applying a point release
-- deserves.

-- The shared secret, encrypted by TotpSecretCipher (AES-256-GCM, keyed from
-- CALCLEANER_DB_KEY with the "totp" context) exactly as
-- calendar_connection.encrypted_credentials is. BLOB, not TEXT, because that
-- is what the cipher emits: IV || ciphertext || tag.
ALTER TABLE app_user ADD COLUMN totp_secret BLOB;

-- Set only once a code generated from totp_secret has been verified, so an
-- abandoned enrolment can never lock somebody out of their own account.
ALTER TABLE app_user ADD COLUMN totp_enabled INTEGER NOT NULL DEFAULT 0;

-- Admin-set. Forces the user through enrolment before they can use the app.
-- Independent of totp_enabled: required-but-not-yet-enrolled is the state that
-- drives the forced enrolment redirect.
ALTER TABLE app_user ADD COLUMN totp_required INTEGER NOT NULL DEFAULT 0;

ALTER TABLE app_user ADD COLUMN totp_confirmed_at TEXT;

-- Replay protection. A TOTP code is valid across the current step and one
-- either side, i.e. up to 90 seconds, so without remembering the last step
-- that was accepted a code observed once - over a shoulder, in a screen share,
-- in a proxy log that captured the POST body - can be replayed for the rest of
-- that window. Verification requires a strictly greater step than this.
ALTER TABLE app_user ADD COLUMN totp_last_step INTEGER;

-- Single-use recovery codes, the way back in when the authenticator is gone.
-- A separate table rather than a delimited column because they are consumed
-- one at a time and the count remaining is shown in the UI.
--
-- code_hash is SHA-256, deliberately NOT bcrypt like password_hash. Verifying
-- an unknown code means testing it against every unused row, so at bcrypt's
-- cost factor a single wrong guess would burn about a second of CPU on an
-- endpoint that is reachable before authentication completes - a trivially
-- weaponised denial of service. The slow-hash property exists to protect
-- low-entropy secrets a human chose; these are generated here with 50 bits of
-- entropy from a SecureRandom, so it buys nothing and costs availability.
CREATE TABLE totp_recovery_code (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    used_at TEXT
);

CREATE INDEX idx_totp_recovery_code_user_id ON totp_recovery_code(user_id);
