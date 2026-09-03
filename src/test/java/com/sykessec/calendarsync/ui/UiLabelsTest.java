package com.sykessec.calendarsync.ui;

import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;
import com.sykessec.calendarsync.ics.ExportProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UiLabelsTest {

    @Test
    void anUntouchedFeedReadsAsDefaultRatherThanThreeEmptySettings() {
        assertThat(UiLabels.summary(ExportProfile.DEFAULT)).isEqualTo("Default (no reminders)");
    }

    @Test
    void namesEverySettingAFeedHasChanged() {
        String summary = UiLabels.summary(new ExportProfile(ExportTarget.OUTLOOK, EventClassification.PRIVATE,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.STRIP, 15));

        assertThat(summary).isEqualTo("Outlook / Microsoft 365 · Private · Out of office · No reminders");
    }

    @Test
    void saysSoWhenTheTargetCannotExpressOutOfOffice() {
        String summary = UiLabels.summary(new ExportProfile(ExportTarget.GOOGLE, EventClassification.UNCHANGED,
                EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.PASSTHROUGH, 15));

        assertThat(summary).contains("Out of office (as busy)");
    }
}
