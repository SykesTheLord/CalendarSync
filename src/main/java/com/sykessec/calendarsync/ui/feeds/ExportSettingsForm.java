package com.sykessec.calendarsync.ui.feeds;

import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;
import com.sykessec.calendarsync.ics.ExportProfile;
import com.sykessec.calendarsync.ui.UiLabels;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;

/**
 * The "how does this feed's output look to a subscriber" half of the feed
 * editor: privacy, busy status and reminders, plus which calendar app the feed
 * is aimed at.
 *
 * Its own component rather than more fields in PublishedFeedsView because it
 * has behaviour of its own - the reminder-offset field only applies to one
 * policy, the target changes what the other settings can actually achieve, and
 * the preview has to be recomputed whenever any of them moves.
 *
 * The preview is the point of the component. Four dropdowns cannot tell anyone
 * what will land in the file, and the difference between "Out of office" that
 * arrives and "Out of office" that silently became plain busy is exactly the
 * kind of thing a user finds out weeks later from a colleague's calendar.
 */
public class ExportSettingsForm extends VerticalLayout {

    /**
     * The Microsoft/Outlook hardening most people come here for: the event
     * blocks time as Out of Office, is marked private, and asks for no
     * reminder on the subscriber's device.
     */
    private static final ExportProfile OUTLOOK_OUT_OF_OFFICE = new ExportProfile(ExportTarget.OUTLOOK,
            EventClassification.PRIVATE, EventBusyStatus.OUT_OF_OFFICE, AlarmPolicy.STRIP, 15);

    private final ComboBox<ExportTarget> target = new ComboBox<>("Calendar app subscribing to this feed");
    private final ComboBox<EventClassification> classification = new ComboBox<>("Privacy");
    private final ComboBox<EventBusyStatus> busyStatus = new ComboBox<>("Show time as");
    private final ComboBox<AlarmPolicy> alarmPolicy = new ComboBox<>("Reminders");
    private final IntegerField alarmMinutes = new IntegerField("Minutes before");
    private final Pre preview = new Pre();

    public ExportSettingsForm() {
        setPadding(false);
        setSpacing(false);

        configure(target, ExportTarget.values(), UiLabels::of);
        configure(classification, EventClassification.values(), UiLabels::of);
        configure(busyStatus, EventBusyStatus.values(), UiLabels::of);
        configure(alarmPolicy, AlarmPolicy.values(), UiLabels::of);

        alarmMinutes.setMin(0);
        alarmMinutes.setMax(ExportProfile.MAX_ALARM_MINUTES);
        alarmMinutes.setStepButtonsVisible(true);

        target.addValueChangeListener(e -> refresh());
        classification.addValueChangeListener(e -> refresh());
        busyStatus.addValueChangeListener(e -> refresh());
        alarmPolicy.addValueChangeListener(e -> refresh());
        alarmMinutes.addValueChangeListener(e -> refresh());

        Button preset = new Button("Use the Outlook out-of-office preset", e -> setProfile(OUTLOOK_OUT_OF_OFFICE));
        preset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        FormLayout form = new FormLayout(target, classification, busyStatus, alarmPolicy, alarmMinutes);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("28em", 2));

        preview.getStyle().set("font-size", "var(--lumo-font-size-xs)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "var(--lumo-space-s)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("white-space", "pre-wrap")
                .set("margin", "0");

        Paragraph previewCaption = new Paragraph("Added to every event this feed exports:");
        previewCaption.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-bottom", "var(--lumo-space-xs)");

        add(new H4("Export settings"), form, preset, previewCaption, preview);
        setProfile(ExportProfile.DEFAULT);
    }

    private <T> void configure(ComboBox<T> field, T[] items, com.vaadin.flow.component.ItemLabelGenerator<T> labels) {
        field.setItems(items);
        field.setItemLabelGenerator(labels);
        // Same reasoning as RuleForm's pickers: an empty value means nothing
        // here, and every option is already listed, so the field doesn't offer
        // being cleared or typed into.
        field.setAllowCustomValue(false);
        field.setClearButtonVisible(false);
    }

    public void setProfile(ExportProfile profile) {
        ExportProfile effective = profile == null ? ExportProfile.DEFAULT : profile;
        target.setValue(effective.target());
        classification.setValue(effective.classification());
        busyStatus.setValue(effective.busyStatus());
        alarmPolicy.setValue(effective.alarmPolicy());
        alarmMinutes.setValue(effective.alarmMinutesBefore());
        refresh();
    }

    /**
     * The settings as entered. Null-tolerant on purpose - ExportProfile reads a
     * missing value as "leave it to the calendar app", which is the same thing
     * an empty field means here.
     */
    public ExportProfile getProfile() {
        return new ExportProfile(target.getValue(), classification.getValue(), busyStatus.getValue(),
                alarmPolicy.getValue(), minutesOrDefault());
    }

    /**
     * Only the reminder offset can be entered wrongly - everything else is a
     * pick from a fixed list. Reported on the field, like the rest of the app,
     * rather than as a notification that names no field.
     */
    public boolean isValid() {
        if (alarmPolicy.getValue() != AlarmPolicy.FIXED) {
            alarmMinutes.setInvalid(false);
            return true;
        }
        Integer minutes = alarmMinutes.getValue();
        if (minutes == null || minutes < 0 || minutes > ExportProfile.MAX_ALARM_MINUTES) {
            alarmMinutes.setInvalid(true);
            alarmMinutes.setErrorMessage("Between 0 and " + ExportProfile.MAX_ALARM_MINUTES + " minutes");
            return false;
        }
        alarmMinutes.setInvalid(false);
        return true;
    }

    private int minutesOrDefault() {
        Integer minutes = alarmMinutes.getValue();
        if (minutes == null || minutes < 0 || minutes > ExportProfile.MAX_ALARM_MINUTES) {
            return ExportProfile.DEFAULT.alarmMinutesBefore();
        }
        return minutes;
    }

    private void refresh() {
        alarmMinutes.setVisible(alarmPolicy.getValue() == AlarmPolicy.FIXED);
        target.setHelperText(UiLabels.help(target.getValue()));
        alarmPolicy.setHelperText(UiLabels.help(alarmPolicy.getValue()));

        ExportProfile profile = getProfile();
        busyStatus.setHelperText(profile.busyStatusIsDowngraded()
                ? "This app ignores Microsoft's extension, so Out of Office is exported as plain busy."
                : "");
        preview.setText(String.join("\n", profile.exportedPropertyLines()));
    }
}
