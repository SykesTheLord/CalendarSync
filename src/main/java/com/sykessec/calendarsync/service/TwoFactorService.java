package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.TotpRecoveryCode;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.repository.TotpRecoveryCodeRepository;
import com.sykessec.calendarsync.security.UserSessionTerminator;
import com.sykessec.calendarsync.util.Base32;
import com.sykessec.calendarsync.util.SqliteTimestamps;
import com.sykessec.calendarsync.util.TokenGenerator;
import com.sykessec.calendarsync.util.Totp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Every state transition for a user's second factor. The one place that writes
 * app_user's totp_* columns and totp_recovery_code, so that "enabled implies a
 * confirmed secret and ten fresh codes" is an invariant of one file rather
 * than something four callers each have to remember.
 *
 * The enable and regenerate paths are @Transactional because they touch two
 * tables and a half-applied result is a locked-out account - the first place
 * in this codebase where that has actually mattered (UserAdminService, which
 * only ever writes one row, has none).
 */
@Service
public class TwoFactorService {

    /** Ten is the number every comparable product settles on, and fits one screen. */
    public static final int RECOVERY_CODE_COUNT = 10;

    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);

    private final AppUserRepository appUserRepository;
    private final TotpRecoveryCodeRepository recoveryCodeRepository;
    private final TotpSecretCipher secretCipher;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionTerminator sessionTerminator;

    public TwoFactorService(AppUserRepository appUserRepository,
                            TotpRecoveryCodeRepository recoveryCodeRepository,
                            TotpSecretCipher secretCipher,
                            PasswordEncoder passwordEncoder,
                            UserSessionTerminator sessionTerminator) {
        this.appUserRepository = appUserRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.secretCipher = secretCipher;
        this.passwordEncoder = passwordEncoder;
        this.sessionTerminator = sessionTerminator;
    }

    /**
     * Confirms an enrolment: the caller supplies the candidate secret it has
     * been holding in the session and a code the user read off their
     * authenticator. The secret is only persisted if that code verifies, so an
     * abandoned or mis-scanned enrolment leaves the account exactly as it was.
     *
     * Returns the plaintext recovery codes - the only time they exist outside a
     * hash, which is why they are returned rather than stored and re-read.
     */
    @Transactional
    public List<String> enable(Long userId, String candidateSecret, String submittedCode) {
        AppUser user = require(userId);
        if (user.isTotpEnabled()) {
            throw new IllegalArgumentException("Two-factor authentication is already switched on for this account");
        }
        if (candidateSecret == null || candidateSecret.isBlank()) {
            throw new IllegalArgumentException("Start the setup again - the enrolment has expired");
        }

        byte[] raw = decodeCandidate(candidateSecret);
        Long step = Totp.verify(raw, submittedCode, nowEpochSeconds());
        if (step == null) {
            throw new IllegalArgumentException(
                    "That code isn't right. Check your authenticator is showing the CalendarSync entry, "
                            + "and that this device's clock is correct.");
        }

        user.setTotpSecret(secretCipher.encode(candidateSecret));
        user.setTotpEnabled(true);
        user.setTotpConfirmedAt(SqliteTimestamps.now());
        user.setTotpLastStep(step);
        appUserRepository.save(user);

        List<String> codes = replaceRecoveryCodes(user.getId());
        log.info("Two-factor authentication enabled for user '{}'", user.getUsername());
        return codes;
    }

    /**
     * Turns the second factor off, requiring the account password to do it.
     *
     * The password check is the point of this method. Everything else in
     * AccountView is reachable by whoever holds the session, but a stolen
     * session that can silently strip the second factor makes the second factor
     * decorative - the attacker simply removes it and keeps the password they
     * already have. Refuses outright when an admin has marked 2FA required.
     */
    @Transactional
    public void disable(Long userId, String currentPassword) {
        AppUser user = require(userId);
        if (user.isTotpRequired()) {
            throw new IllegalArgumentException(
                    "An administrator has made two-factor authentication mandatory for this account");
        }
        if (!user.isTotpEnabled()) {
            throw new IllegalArgumentException("Two-factor authentication is not switched on");
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("That password is not correct");
        }
        clearState(user);
        appUserRepository.save(user);
        log.info("Two-factor authentication disabled by user '{}'", user.getUsername());
    }

    /** Issues a fresh set and invalidates every previous code. */
    @Transactional
    public List<String> regenerateRecoveryCodes(Long userId, String currentPassword) {
        AppUser user = require(userId);
        if (!user.isTotpEnabled()) {
            throw new IllegalArgumentException("Two-factor authentication is not switched on");
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("That password is not correct");
        }
        log.info("Recovery codes regenerated for user '{}'", user.getUsername());
        return replaceRecoveryCodes(user.getId());
    }

    /**
     * The admin way back in for somebody who has lost their authenticator and
     * their codes. Terminates their sessions for the same reason
     * UserAdminService.resetPassword does: the credential situation just
     * changed underneath anyone already signed in.
     */
    @Transactional
    public void clearForUser(Long userId) {
        AppUser user = require(userId);
        clearState(user);
        user.setTotpRequired(false);
        appUserRepository.save(user);
        sessionTerminator.terminateSessionsFor(user.getId());
        log.warn("Two-factor authentication cleared by an administrator for user '{}'", user.getUsername());
    }

    /**
     * Marks 2FA mandatory. Does not enrol anybody - it makes the next sign-in
     * route them through setup before they can reach anything else.
     */
    @Transactional
    public void setRequired(Long userId, boolean required) {
        AppUser user = require(userId);
        user.setTotpRequired(required);
        appUserRepository.save(user);
        log.info("Two-factor authentication {} for user '{}'", required ? "required" : "no longer required",
                user.getUsername());
    }

    public long remainingRecoveryCodes(Long userId) {
        return recoveryCodeRepository.countByUserIdAndUsedAtIsNull(userId);
    }

    /**
     * Verifies a TOTP code at sign-in and refuses a replay.
     *
     * The step comparison is the replay guard: a code is valid across the
     * current step and one either side, so accepting any step that merely
     * verifies would let the same six digits be presented again for up to 90
     * seconds. Requiring a strictly greater step than the last one accepted
     * means each code works exactly once.
     */
    @Transactional
    public boolean verifyCode(AppUser user, String submittedCode) {
        byte[] raw = secretCipher.decodeToRawSecret(user.getTotpSecret());
        if (raw == null) {
            return false;
        }
        Long step = Totp.verify(raw, submittedCode, nowEpochSeconds());
        if (step == null) {
            return false;
        }
        Long last = user.getTotpLastStep();
        if (last != null && step <= last) {
            log.warn("Rejected a replayed two-factor code for user '{}'", user.getUsername());
            return false;
        }
        user.setTotpLastStep(step);
        appUserRepository.save(user);
        return true;
    }

    /**
     * Burns a recovery code. Compares against every unused row rather than
     * looking the hash up directly, so the scoping stays "this user's codes"
     * rather than "any row whose hash matches".
     */
    @Transactional
    public boolean consumeRecoveryCode(AppUser user, String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return false;
        }
        String hash = hash(submitted);
        for (TotpRecoveryCode candidate : recoveryCodeRepository.findAllByUserIdAndUsedAtIsNull(user.getId())) {
            if (MessageDigest.isEqual(candidate.getCodeHash().getBytes(StandardCharsets.UTF_8),
                    hash.getBytes(StandardCharsets.UTF_8))) {
                candidate.setUsedAt(SqliteTimestamps.now());
                recoveryCodeRepository.save(candidate);
                log.warn("User '{}' signed in with a recovery code; {} remain",
                        user.getUsername(), remainingRecoveryCodes(user.getId()));
                return true;
            }
        }
        return false;
    }

    private List<String> replaceRecoveryCodes(Long userId) {
        recoveryCodeRepository.deleteAllByUserId(userId);
        List<String> plaintext = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = TokenGenerator.recoveryCode();
            plaintext.add(code);
            TotpRecoveryCode row = new TotpRecoveryCode();
            row.setUserId(userId);
            row.setCodeHash(hash(code));
            recoveryCodeRepository.save(row);
        }
        return plaintext;
    }

    private void clearState(AppUser user) {
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpConfirmedAt(null);
        user.setTotpLastStep(null);
        recoveryCodeRepository.deleteAllByUserId(user.getId());
    }

    private byte[] decodeCandidate(String candidateSecret) {
        try {
            return Base32.decode(candidateSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Start the setup again - that secret is not readable", e);
        }
    }

    /**
     * Normalised before hashing so that the dashes and casing a user types back
     * do not decide whether their recovery code works.
     */
    private String hash(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private AppUser require(Long userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such user"));
    }

    long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }
}
