package com.sykessec.calendarsync.ui.feeds;

import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;
import com.sykessec.calendarsync.ics.ExportProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructs the component directly rather than going through a rendered view:
 * everything worth testing here is field state, and a plain unit test avoids
 * the vaadin-dev-server dependency that requesting a view drags in.
 */
class ExportSettingsFormTest {

    @Test
    void startsOnTheDefaultsSoANewFeedBehavesLikeEveryExistingOne() {
        ExportSettingsForm form = new ExportSettingsForm();

        assertThat(form.getProfile()).isEqualTo(ExportProfile.DEFAULT);
        assertThat(form.isValid()).isTrue();
    }

    @Test
    void roundTripsAProfileThroughTheFields() {
        ExportProfile profile = new ExportProfile(ExportTarget.OUTLOOK, EventClassification.PRIVATE,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.FIXED, 30);

        ExportSettingsForm form = new ExportSettingsForm();
        form.setProfile(profile);

        assertThat(form.getProfile()).isEqualTo(profile);
    }

    @Test
    void aNullProfileLoadsTheDefaultsRatherThanBlankingEveryField() {
        ExportSettingsForm form = new ExportSettingsForm();
        form.setProfile(null);

        assertThat(form.getProfile()).isEqualTo(ExportProfile.DEFAULT);
    }
}
