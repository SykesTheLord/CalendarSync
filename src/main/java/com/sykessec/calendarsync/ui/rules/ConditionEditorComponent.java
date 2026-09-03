package com.sykessec.calendarsync.ui.rules;

import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.service.DeletionRuleService;
import com.sykessec.calendarsync.ui.UiLabels;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Repeatable field group for a rule's conditions. The operator list is
 * narrowed to what the chosen field's evaluator actually supports - offering
 * every operator for every field meant most combinations were invalid and only
 * said so after the user pressed Add.
 */
public class ConditionEditorComponent extends VerticalLayout {

    private final DeletionRuleService ruleService;
    private final Long ruleId;
    private final Grid<RuleCondition> grid = new Grid<>(RuleCondition.class, false);
    private final Paragraph empty = new Paragraph();

    public ConditionEditorComponent(DeletionRuleService ruleService, Long ruleId) {
        this.ruleService = ruleService;
        this.ruleId = ruleId;

        grid.addColumn(c -> UiLabels.of(c.getField())).setHeader("Field").setAutoWidth(true);
        grid.addColumn(c -> UiLabels.of(c.getOperator())).setHeader("Operator").setAutoWidth(true);
        grid.addColumn(RuleCondition::getValue).setHeader("Value").setFlexGrow(1);
        grid.addColumn(c -> UiLabels.yesNo(c.isCaseSensitive())).setHeader("Case sensitive").setAutoWidth(true);
        grid.addComponentColumn(condition -> {
            Button remove = new Button("Remove", e -> {
                ruleService.deleteCondition(condition.getId());
                refresh();
                Notification.show("Condition removed");
            });
            remove.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
            return remove;
        }).setFlexGrow(0).setWidth("8em");
        grid.setAllRowsVisible(true);

        empty.setText("No conditions yet - a rule with no conditions never matches anything.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        ComboBox<RuleField> field = new ComboBox<>("Field");
        field.setItems(RuleField.values());
        field.setItemLabelGenerator(UiLabels::of);

        ComboBox<RuleOperator> operator = new ComboBox<>("Operator");
        operator.setItemLabelGenerator(UiLabels::of);
        operator.setEnabled(false);
        operator.setHelperText("Pick a field first");

        TextField value = new TextField("Value");
        value.setWidth("18em");
        Checkbox caseSensitive = new Checkbox("Case sensitive");

        // The operator's helper text has two jobs - "pick a field first" while
        // there is no field, and the negation caveat once an operator is
        // chosen - and BOTH listeners below can be the last to run: clearing
        // the field calls operator.clear(), which fires the operator's own
        // listener. Deriving the text from the current state of both fields,
        // rather than from whichever event just arrived, is what stops the two
        // listeners overwriting each other's message.
        Runnable syncOperatorHelp = () -> {
            if (field.getValue() == null) {
                operator.setHelperText("Pick a field first");
            } else {
                operator.setHelperText(UiLabels.negationCaveat(operator.getValue(), field.getValue()));
            }
        };

        field.addValueChangeListener(e -> {
            RuleField chosen = e.getValue();
            operator.setEnabled(chosen != null);
            operator.setItems(chosen == null ? java.util.Set.of() : ruleService.supportedOperators(chosen));
            operator.clear();
            syncOperatorHelp.run();
            // "Is longer than 30" reads as minutes, "is before 2026-01-01" as
            // a date - the field decides what a valid value even looks like.
            value.setHelperText(valueHintFor(chosen));
            caseSensitive.setVisible(isTextField(chosen));
        });
        // A negated operator also matches events where the field is empty or
        // absent, which is correct and is the thing people do not expect. Said
        // here, while the operator is being chosen, rather than left to be
        // learned from a DELETE rule that matched more than its author meant.
        operator.addValueChangeListener(e -> syncOperatorHelp.run());
        caseSensitive.setVisible(false);

        Button addCondition = new Button("Add condition", e -> {
            // Empty inputs used to return silently, so pressing Add with
            // nothing filled in did nothing at all and gave no reason why.
            boolean valid = true;
            if (field.getValue() == null) {
                field.setInvalid(true);
                field.setErrorMessage("Pick a field");
                valid = false;
            } else {
                field.setInvalid(false);
            }
            if (operator.getValue() == null) {
                operator.setInvalid(true);
                operator.setErrorMessage("Pick an operator");
                valid = false;
            } else {
                operator.setInvalid(false);
            }
            if (value.getValue() == null || value.getValue().isBlank()) {
                value.setInvalid(true);
                value.setErrorMessage("Enter a value to match against");
                valid = false;
            } else {
                value.setInvalid(false);
            }
            if (!valid) {
                return;
            }

            try {
                // Rejects an operator the field doesn't support AND a value it
                // can't parse (an unparseable instant, a non-numeric duration,
                // an invalid regex). Both used to be accepted here and only
                // blew up later, inside the background sync job.
                ruleService.addCondition(ruleId, field.getValue(), operator.getValue(),
                        value.getValue(), caseSensitive.getValue());
            } catch (IllegalArgumentException ex) {
                value.setInvalid(true);
                value.setErrorMessage(ex.getMessage());
                Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            value.setInvalid(false);
            field.clear();
            operator.clear();
            value.clear();
            caseSensitive.setValue(false);
            refresh();
            Notification.show("Condition added");
        });

        // The inputs carry labels and the checkbox and button don't, so the row
        // is aligned on its baseline rather than its top; it wraps instead of
        // squeezing the value field to nothing when the dialog is narrow.
        HorizontalLayout newCondition = new HorizontalLayout(field, operator, value, caseSensitive, addCondition);
        newCondition.setWidthFull();
        newCondition.setAlignItems(Alignment.BASELINE);
        newCondition.getStyle().set("flex-wrap", "wrap");
        // setFlexGrow rather than expand(): expand() also forces width:100%,
        // which makes the value field claim a row of its own even when the
        // whole group would have fitted on one line.
        newCondition.setFlexGrow(1, value);

        setPadding(false);
        setWidthFull();
        add(grid, empty, newCondition);
        refresh();
    }

    private static boolean isTextField(RuleField field) {
        return field == RuleField.TITLE || field == RuleField.DESCRIPTION
                || field == RuleField.LOCATION || field == RuleField.CALENDAR_NAME
                || field == RuleField.ATTENDEE;
    }

    private static String valueHintFor(RuleField field) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case DURATION -> "A whole number of minutes, e.g. 30";
            case START -> "An ISO-8601 instant, e.g. 2026-01-01T09:00:00Z";
            case RECURRENCE -> "true or false";
            default -> null;
        };
    }

    private void refresh() {
        var conditions = ruleService.conditionsFor(ruleId);
        grid.setItems(conditions);
        grid.setVisible(!conditions.isEmpty());
        empty.setVisible(conditions.isEmpty());
    }
}
