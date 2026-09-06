package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.ui.account.TwoFactorSetupView;
import com.sykessec.calendarsync.ui.connections.ConnectionsView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the decision, not the Vaadin wiring around it: constructing a real
 * BeforeEnterEvent needs a router and a UI, and the two lines that call this
 * are stock framework API.
 */
class ForcedEnrolmentInitializerTest {

    @Test
    void divertsAUserWhoIsRequiredToEnrolButHasNot() {
        assertThat(ForcedEnrolmentInitializer.shouldForceEnrolment(ConnectionsView.class, user(true, false)))
                .isTrue();
    }

    @Test
    void leavesEverybodyElseAlone() {
        assertThat(ForcedEnrolmentInitializer.shouldForceEnrolment(ConnectionsView.class, user(false, false)))
                .isFalse();
        assertThat(ForcedEnrolmentInitializer.shouldForceEnrolment(ConnectionsView.class, user(true, true)))
                .isFalse();
        assertThat(ForcedEnrolmentInitializer.shouldForceEnrolment(ConnectionsView.class, user(false, true)))
                .isFalse();
    }

    @Test
    void neverDivertsTheSetupViewToItself() {
        // Without this exemption the user is forwarded to a page that forwards
        // them again, and the account is unusable rather than merely nagged.
        assertThat(ForcedEnrolmentInitializer.shouldForceEnrolment(TwoFactorSetupView.class, user(true, false)))
                .isFalse();
    }

    @Test
    void toleratesNoSignedInUser() {
        // The listener runs for anonymous navigation too - the login page is a
        // route like any other.
        assertThat(ForcedEnrolmentInitializer.shouldForceEnrolment(ConnectionsView.class, null)).isFalse();
    }

    private static AppUser user(boolean required, boolean enabled) {
        AppUser user = new AppUser();
        user.setTotpRequired(required);
        user.setTotpEnabled(enabled);
        return user;
    }
}
