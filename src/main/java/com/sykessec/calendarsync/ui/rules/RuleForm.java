package com.sykessec.calendarsync.ui.rules;

import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.service.CalendarService;
import com.sykessec.calendarsync.service.DeletionRuleService;
import com.sykessec.calendarsync.service.PublishedFeedService;
import com.sykessec.calendarsync.ui.UiLabels;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;

public class RuleForm extends VerticalLayout {

    private final HorizontalLayout actions;

    public RuleForm(DeletionRuleService ruleService, CalendarService calendarService,
                     PublishedFeedService feedService, DeletionRule rule, Runnable onClose) {
        TextField name = new TextField("Name");
        name.setValue(rule.getName() == null ? "" : rule.getName());
        name.setRequiredIndicatorVisible(true);

        ComboBox<MatchLogic> matchLogic = new ComboBox<>("Match logic");
        matchLogic.setItems(MatchLogic.values());
        matchLogic.setItemLabelGenerator(UiLabels::of);
        matchLogic.setValue(rule.getMatchLogic());
        matchLogic.setRequiredIndicatorVisible(true);
        // Nothing sensible happens with no value, and these are the only two
        // options - so the field simply doesn't offer being emptied.
        matchLogic.setAllowCustomValue(false);
        matchLogic.setClearButtonVisible(false);

        ComboBox<RuleAction> action = new ComboBox<>("Action");
        action.setItems(RuleAction.values());
        action.setItemLabelGenerator(UiLabels::of);
        action.setValue(rule.getAction());
        action.setRequiredIndicatorVisible(true);
        action.setAllowCustomValue(false);
        action.setClearButtonVisible(false);
        action.setHelperText("New rules default to a dry run - nothing is ever deleted until "
                + "you explicitly switch a rule to Delete.");

        IntegerField priority = new IntegerField("Priority");
        priority.setValue(rule.getPriority());
        priority.setHelperText("Lower runs first.");

        Checkbox enabled = new Checkbox("Enabled");
        enabled.setValue(rule.isEnabled());

        FormLayout form = new FormLayout(name, matchLogic, action, priority, enabled);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("30em", 2),
                new FormLayout.ResponsiveStep("50em", 3));

        Button save = new Button("Save", e -> {
            // Every required field is checked before anything is written. The
            // ComboBoxes used to go unchecked, so a cleared one wrote null
            // into a NOT NULL column and surfaced as Vaadin's generic
            // internal-error overlay rather than as a message about the field.
            boolean valid = true;
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true);
                name.setErrorMessage("A rule needs a name");
                valid = false;
            } else {
                name.setInvalid(false);
            }
            valid &= requireChosen(matchLogic, "Choose how conditions combine");
            valid &= requireChosen(action, "Choose what this rule does");
            if (!valid) {
                return;
            }

            // Snapshot the current values so a rejected save doesn't leave the
            // in-memory rule mutated with settings that were never persisted.
            MatchLogic previousLogic = rule.getMatchLogic();
            RuleAction previousAction = rule.getAction();
            int previousPriority = rule.getPriority();
            boolean previousEnabled = rule.isEnabled();
            String previousName = rule.getName();

            rule.setName(name.getValue().trim());
            rule.setMatchLogic(matchLogic.getValue());
            rule.setAction(action.getValue());
            rule.setPriority(priority.getValue() == null ? 0 : priority.getValue());
            rule.setEnabled(enabled.getValue());
            try {
                ruleService.save(rule);
            } catch (IllegalArgumentException ex) {
                // Switching a rule to DELETE while it is scoped to a read-only
                // calendar is rejected here, and the rejection message is
                // written for the user - it needs to reach them rather than
                // becoming an internal-error overlay.
                rule.setName(previousName);
                rule.setMatchLogic(previousLogic);
                rule.setAction(previousAction);
                rule.setPriority(previousPriority);
                rule.setEnabled(previousEnabled);
                action.setValue(previousAction);
                Notification.show(ex.getMessage(), 8000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            onClose.run();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button close = new Button("Close", e -> onClose.run());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        actions = new HorizontalLayout(close, save);

        Paragraph savedNote = new Paragraph("Conditions and scope below are saved as soon as you add or "
                + "remove them. Save applies the settings above.");
        savedNote.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        setPadding(false);
        setWidthFull();
        add(form, savedNote, new Hr(),
                new H3("Conditions"),
                new ConditionEditorComponent(ruleService, rule.getId()),
                new Hr(),
                new H3("Scope"),
                new Paragraph("A rule with no scope never matches anything. Scope a rule to a "
                        + "calendar for real deletion (refused for read-only calendars), or to a published "
                        + "feed to filter it (the only way a read-only source's events get excluded)."),
                new ScopeEditorComponent(ruleService, calendarService, feedService, rule.getId()));
    }

    /** The save/close pair, for the hosting dialog's footer. */
    public HorizontalLayout actions() {
        return actions;
    }

    private static boolean requireChosen(ComboBox<?> field, String message) {
        if (field.getValue() == null) {
            field.setInvalid(true);
            field.setErrorMessage(message);
            return false;
        }
        field.setInvalid(false);
        return true;
    }
}
