package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.AbstractIntegrationTest;
import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.util.Base32;
import com.sykessec.calendarsync.util.TokenGenerator;
import com.sykessec.calendarsync.util.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state machine around the second factor, against a real database - the
 * enrolment and recovery paths write two tables and the invariant worth
 * protecting ("enabled implies a confirmed secret and a set of codes") spans
 * both.
 */
class TwoFactorServiceTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-perfectly-fine-password";

    @Autowired
    private TwoFactorService twoFactorService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aWrongCodeLeavesTheAccountCompletelyUnchanged() {
        AppUser user = createUser();
        String secret = Totp.generateSecret();

        assertThatThrownBy(() -> twoFactorService.enable(user.getId(), secret, "000000"))
                .isInstanceOf(IllegalArgumentException.class);

        // Nothing half-written: no secret, not enabled, no codes. A mis-scanned
        // QR must not leave a row that can lock somebody out.
        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isTotpEnabled()).isFalse();
        assertThat(reloaded.getTotpSecret()).isNull();
        assertThat(twoFactorService.remainingRecoveryCodes(user.getId())).isZero();
    }

    @Test
    void enablingStoresTheSecretAndIssuesTenCodes() {
        AppUser user = createUser();
        List<String> codes = enrol(user);

        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isTotpEnabled()).isTrue();
        assertThat(reloaded.getTotpSecret()).isNotNull();
        assertThat(reloaded.getTotpConfirmedAt()).isNotBlank();
        assertThat(codes).hasSize(TwoFactorService.RECOVERY_CODE_COUNT).doesNotHaveDuplicates();
        assertThat(twoFactorService.remainingRecoveryCodes(user.getId()))
                .isEqualTo(TwoFactorService.RECOVERY_CODE_COUNT);
    }

    @Test
    void theConfirmingCodeCannotImmediatelyBeReusedToSignIn() {
        AppUser user = createUser();
        String secret = Totp.generateSecret();
        long step = Totp.timeStep(Instant.now().getEpochSecond());
        String code = Totp.codeAt(Base32.decode(secret), step);

        twoFactorService.enable(user.getId(), secret, code);

        // Enrolment consumed that step. The same code is still arithmetically
        // valid for another minute, so only the recorded last-step check stops
        // it being replayed.
        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(twoFactorService.verifyCode(reloaded, code)).isFalse();
    }

    @Test
    void aCodeFromTheNextStepIsAccepted() {
        AppUser user = createUser();
        String secret = Totp.generateSecret();
        long step = Totp.timeStep(Instant.now().getEpochSecond());
        twoFactorService.enable(user.getId(), secret, Totp.codeAt(Base32.decode(secret), step - 1));

        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(twoFactorService.verifyCode(reloaded, Totp.codeAt(Base32.decode(secret), step))).isTrue();
    }

    @Test
    void aRecoveryCodeIsAcceptedOnceAndOnlyOnce() {
        AppUser user = createUser();
        String code = enrol(user).get(3);
        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();

        assertThat(twoFactorService.consumeRecoveryCode(reloaded, code)).isTrue();
        assertThat(twoFactorService.consumeRecoveryCode(reloaded, code)).isFalse();
        assertThat(twoFactorService.remainingRecoveryCodes(user.getId()))
                .isEqualTo(TwoFactorService.RECOVERY_CODE_COUNT - 1);
    }

    @Test
    void aRecoveryCodeIsAcceptedHoweverTheUserRetypesIt() {
        AppUser user = createUser();
        String code = enrol(user).get(0);
        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();

        // Lowercase, spaced, dashes removed - a code copied off paper.
        String mangled = "  " + code.replace("-", "").toLowerCase() + " ";
        assertThat(twoFactorService.consumeRecoveryCode(reloaded, mangled)).isTrue();
    }

    @Test
    void oneUsersRecoveryCodeDoesNothingForAnother() {
        AppUser owner = createUser();
        AppUser other = createUser();
        String code = enrol(owner).get(0);
        enrol(other);

        AppUser reloadedOther = appUserRepository.findById(other.getId()).orElseThrow();
        assertThat(twoFactorService.consumeRecoveryCode(reloadedOther, code)).isFalse();
    }

    @Test
    void regeneratingInvalidatesEveryPreviousCode() {
        AppUser user = createUser();
        String oldCode = enrol(user).get(0);

        twoFactorService.regenerateRecoveryCodes(user.getId(), PASSWORD);

        AppUser reloaded = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(twoFactorService.consumeRecoveryCode(reloaded, oldCode)).isFalse();
        assertThat(twoFactorService.remainingRecoveryCodes(user.getId()))
                .isEqualTo(TwoFactorService.RECOVERY_CODE_COUNT);
    }

    @Test
    void turningItOffNeedsThePassword() {
        AppUser user = createUser();
        enrol(user);

        assertThatThrownBy(() -> twoFactorService.disable(user.getId(), "not-the-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        // Still on: a stolen session must not be able to strip the second
        // factor, or the second factor protects nothing once a session leaks.
        assertThat(appUserRepository.findById(user.getId()).orElseThrow().isTotpEnabled()).isTrue();

        twoFactorService.disable(user.getId(), PASSWORD);
        AppUser off = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(off.isTotpEnabled()).isFalse();
        assertThat(off.getTotpSecret()).isNull();
        assertThat(twoFactorService.remainingRecoveryCodes(user.getId())).isZero();
    }

    @Test
    void aUserCannotTurnOffWhatAnAdministratorRequires() {
        AppUser user = createUser();
        enrol(user);
        twoFactorService.setRequired(user.getId(), true);

        assertThatThrownBy(() -> twoFactorService.disable(user.getId(), PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("administrator");
    }

    @Test
    void anAdminClearCleansUpEverythingIncludingTheRequirement() {
        AppUser user = createUser();
        enrol(user);
        twoFactorService.setRequired(user.getId(), true);

        twoFactorService.clearForUser(user.getId());

        AppUser cleared = appUserRepository.findById(user.getId()).orElseThrow();
        assertThat(cleared.isTotpEnabled()).isFalse();
        assertThat(cleared.getTotpSecret()).isNull();
        // The requirement is lifted too: leaving it set would bounce the user
        // straight back into enrolment, which is not what "clear it, they are
        // locked out" is asking for.
        assertThat(cleared.isTotpRequired()).isFalse();
        assertThat(twoFactorService.remainingRecoveryCodes(user.getId())).isZero();
    }

    private List<String> enrol(AppUser user) {
        String secret = Totp.generateSecret();
        long previousStep = Totp.timeStep(Instant.now().getEpochSecond()) - 1;
        return twoFactorService.enable(user.getId(), secret, Totp.codeAt(Base32.decode(secret), previousStep));
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setUsername("tfs-" + TokenGenerator.urlSafeToken(6));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        return appUserRepository.save(user);
    }
}
