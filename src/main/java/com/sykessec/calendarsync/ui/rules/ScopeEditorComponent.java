package com.sykessec.calendarsync.ui.rules;

import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.RuleScope;
import com.sykessec.calendarsync.service.CalendarService;
import com.sykessec.calendarsync.service.CalendarSummary;
import com.sykessec.calendarsync.service.DeletionRuleService;
import com.sykessec.calendarsync.service.PublishedFeedService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A rule with zero scope rows never matches anything - this is what lets a
 * user actually target a rule at a calendar (real deletion, refused for
 * read-only calendars) or a published feed (filtering, the only way an
 * ICS_SOURCE or any other read-only calendar's events ever get excluded).
 */
public class ScopeEditorComponent extends VerticalLayout {

    private final DeletionRuleService ruleService;
    private final CalendarService calendarService;
    private final PublishedFeedService feedService;
    private final Long ruleId;
    private final Grid<RuleScope> grid = new Grid<>(RuleScope.class, false);
    private final Paragraph empty = new Paragraph();

    private final ComboBox<CalendarSummary> calendarPicker = new ComboBox<>("Calendar");
    private final ComboBox<PublishedFeed> feedPicker = new ComboBox<>("Published feed");

    /**
     * Looked up once per refresh and reused by every row's renderer. describe()
     * used to build the whole id-to-calendar map inside the cell renderer, so
     * listing the user's calendars and feeds ran once per row per repaint.
     */
    private Map<Long, CalendarSummary> calendarsById = Map.of();
    private Map<Long, PublishedFeed> feedsById = Map.of();

    public ScopeEditorComponent(DeletionRuleService ruleService, CalendarService calendarService,
                                 PublishedFeedService feedService, Long ruleId) {
        this.ruleService = ruleService;
        this.calendarService = calendarService;
        this.feedService = feedService;
        this.ruleId = ruleId;

        grid.addColumn(this::describe).setHeader("Scope").setFlexGrow(1);
        grid.addComponentColumn(scope -> {
            Button remove = new Button("Remove", e -> {
                ruleService.removeScope(scope.getId());
                refresh();
                Notification.show("Scope removed");
            });
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            return remove;
        }).setFlexGrow(0).setWidth("8em");
        grid.setAllRowsVisible(true);

        empty.setText("Not scoped to anything yet - this rule currently matches nothing.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        calendarPicker.setWidth("24em");
        calendarPicker.setItemLabelGenerator(c -> c.connectionDisplayName() + " – " + c.calendarName());
        Button addCalendar = new Button("Add calendar scope", e -> {
            // Silently returning on an empty picker made the button look
            // broken; it now says which choice is missing.
            if (calendarPicker.getValue() == null) {
                calendarPicker.setInvalid(true);
                calendarPicker.setErrorMessage("Choose a calendar first");
                return;
            }
            calendarPicker.setInvalid(false);
            try {
                ruleService.addCalendarScope(ruleId, calendarPicker.getValue().calendarId());
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 8000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            calendarPicker.clear();
            refresh();
            Notification.show("Calendar scope added");
        });

        feedPicker.setWidth("24em");
        feedPicker.setItemLabelGenerator(PublishedFeed::getName);
        Button addFeed = new Button("Add feed scope", e -> {
            if (feedPicker.getValue() == null) {
                feedPicker.setInvalid(true);
                feedPicker.setErrorMessage("Choose a published feed first");
                return;
            }
            feedPicker.setInvalid(false);
            try {
                ruleService.addFeedScope(ruleId, feedPicker.getValue().getId());
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 8000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            feedPicker.clear();
            refresh();
            Notification.show("Feed scope added");
        });

        setPadding(false);
        setWidthFull();
        add(grid, empty, pickerRow(calendarPicker, addCalendar), pickerRow(feedPicker, addFeed));
        refresh();
    }

    /**
     * The picker carries a label and the button doesn't, so the row lines up
     * on its baseline; the picker takes the leftover width because a calendar
     * label is "connection - calendar" and gets long.
     */
    private HorizontalLayout pickerRow(ComboBox<?> picker, Button button) {
        HorizontalLayout row = new HorizontalLayout(picker, button);
        row.setWidthFull();
        row.setAlignItems(Alignment.BASELINE);
        row.getStyle().set("flex-wrap", "wrap");
        row.setFlexGrow(1, picker);
        return row;
    }

    private String describe(RuleScope scope) {
        if (scope.getCalendarId() != null) {
            CalendarSummary summary = calendarsById.get(scope.getCalendarId());
            return summary == null ? "Calendar #" + scope.getCalendarId()
                    : "Calendar: " + summary.connectionDisplayName() + " – " + summary.calendarName();
        }
        if (scope.getPublishedFeedId() != null) {
            PublishedFeed feed = feedsById.get(scope.getPublishedFeedId());
            return feed == null ? "Feed #" + scope.getPublishedFeedId() : "Feed: " + feed.getName();
        }
        return "(unscoped)";
    }

    private void refresh() {
        List<CalendarSummary> calendars = calendarService.listForCurrentUser();
        List<PublishedFeed> feeds = feedService.listForCurrentUser();
        calendarsById = calendars.stream()
                .collect(Collectors.toMap(CalendarSummary::calendarId, c -> c));
        feedsById = feeds.stream()
                .collect(Collectors.toMap(PublishedFeed::getId, f -> f));

        calendarPicker.setItems(calendars);
        calendarPicker.setEnabled(!calendars.isEmpty());
        calendarPicker.setHelperText(calendars.isEmpty() ? "No calendars have synced yet" : null);
        feedPicker.setItems(feeds);
        feedPicker.setEnabled(!feeds.isEmpty());
        feedPicker.setHelperText(feeds.isEmpty() ? "No published feeds yet" : null);

        List<RuleScope> scopes = ruleService.scopesFor(ruleId);
        grid.setItems(scopes);
        grid.setVisible(!scopes.isEmpty());
        empty.setVisible(scopes.isEmpty());
    }
}
