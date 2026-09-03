package com.sykessec.calendarsync.ui.rules;

import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.service.CalendarService;
import com.sykessec.calendarsync.service.DeletionRuleService;
import com.sykessec.calendarsync.service.PublishedFeedService;
import com.sykessec.calendarsync.ui.Confirm;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.UiLabels;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

@Route(value = "rules", layout = MainLayout.class)
@PageTitle("Rules | CalendarSync")
@PermitAll
public class RulesView extends VerticalLayout {

    private final DeletionRuleService ruleService;
    private final CalendarService calendarService;
    private final PublishedFeedService feedService;
    private final Grid<DeletionRule> grid = new Grid<>(DeletionRule.class, false);
    private final Paragraph empty = new Paragraph();

    public RulesView(DeletionRuleService ruleService, CalendarService calendarService,
                      PublishedFeedService feedService) {
        this.ruleService = ruleService;
        this.calendarService = calendarService;
        this.feedService = feedService;

        setSizeFull();

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_ROW_BORDERS);
        grid.addColumn(DeletionRule::getName).setHeader("Name").setFlexGrow(1);
        grid.addColumn(r -> UiLabels.of(r.getMatchLogic())).setHeader("Match logic").setAutoWidth(true);
        grid.addColumn(r -> UiLabels.of(r.getAction())).setHeader("Action").setAutoWidth(true);
        grid.addColumn(r -> UiLabels.yesNo(r.isEnabled())).setHeader("Enabled").setAutoWidth(true);
        grid.addColumn(DeletionRule::getPriority).setHeader("Priority").setAutoWidth(true);
        grid.addComponentColumn(rule -> {
            Button edit = new Button("Edit", e -> openForm(rule));
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            Button delete = new Button("Delete", e -> Confirm.destructive("Delete this rule?",
                    "\"" + rule.getName() + "\" and its conditions and scopes will be removed. Events it "
                            + "was excluding from a published feed will start appearing there again. "
                            + "This cannot be undone.",
                    "Delete rule",
                    () -> {
                        ruleService.delete(rule.getId());
                        refresh();
                        Notification.show("Rule deleted");
                    }));
            delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            return new HorizontalLayout(edit, delete);
        }).setHeader("").setFlexGrow(0).setAutoWidth(true);
        grid.setSizeFull();

        Button add = new Button("Add rule", e -> openCreateDialog());
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        empty.setText("No rules yet. A rule decides which events get removed - add one to get started. "
                + "New rules start as a dry run, so nothing is deleted until you say so.");

        add(new ViewHeader(VaadinIcon.FILTER, "Rules",
                        "Define what gets removed - new rules default to a safe dry run."),
                add, empty, grid);
        setFlexGrow(1, grid);
        refresh();
    }

    /**
     * Asks for a name before creating anything.
     *
     * "Add rule" used to write a rule called "New rule" to the database and
     * then open the editor, so closing that editor with Escape - which a
     * dialog does by default - left a rule nobody asked for sitting in the
     * list. The conditions and scope editors do need a persisted id to attach
     * rows to, so the row still has to exist before they open; the fix is to
     * make creating it a decision the user made rather than a side effect of
     * looking at the form.
     */
    private void openCreateDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New rule");
        dialog.setWidth("28em");
        dialog.setMaxWidth("95vw");

        com.vaadin.flow.component.textfield.TextField name =
                new com.vaadin.flow.component.textfield.TextField("Name");
        name.setWidthFull();
        name.setRequiredIndicatorVisible(true);
        name.setHelperText("What this rule is for, e.g. \"Drop cancelled lectures\".");

        Button create = new Button("Create", e -> {
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true);
                name.setErrorMessage("A rule needs a name");
                return;
            }
            DeletionRule rule = ruleService.create(name.getValue().trim(), MatchLogic.ANY, 0);
            refresh();
            dialog.close();
            openForm(rule);
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.add(name);
        dialog.getFooter().add(cancel, create);
        dialog.open();
    }

    private void openForm(DeletionRule rule) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Edit rule");
        // The condition and scope editors are each a full row of inputs plus
        // their button, which 40em cut in half - they need the width of a
        // page, not of a small form dialog.
        dialog.setWidth("70em");
        dialog.setMaxWidth("95vw");
        dialog.setMaxHeight("90vh");
        dialog.setResizable(true);
        dialog.setDraggable(true);

        RuleForm form = new RuleForm(ruleService, calendarService, feedService, rule, () -> {
            refresh();
            dialog.close();
        });
        dialog.add(form);
        // In the footer rather than mid-form: they used to sit between the
        // rule's fields and its conditions, so they scrolled out of sight
        // exactly when the user reached the scope editor at the bottom.
        dialog.getFooter().add(form.actions());
        // Conditions and scopes save the moment they are added, so the grid
        // behind can be out of date however the dialog is dismissed.
        dialog.addDialogCloseActionListener(e -> {
            refresh();
            dialog.close();
        });
        dialog.open();
    }

    private void refresh() {
        var rules = ruleService.listForCurrentUser();
        grid.setItems(rules);
        grid.setVisible(!rules.isEmpty());
        empty.setVisible(rules.isEmpty());
    }
}
