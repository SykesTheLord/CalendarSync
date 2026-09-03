package com.sykessec.calendarsync.ui.feeds;

import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.ics.ExportProfile;
import com.sykessec.calendarsync.service.CalendarService;
import com.sykessec.calendarsync.service.CalendarSummary;
import com.sykessec.calendarsync.service.PublishedFeedService;
import com.sykessec.calendarsync.ui.Clipboard;
import com.sykessec.calendarsync.ui.Confirm;
import com.sykessec.calendarsync.ui.DisplayTime;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.SubscribeUrlDialog;
import com.sykessec.calendarsync.ui.UiLabels;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Create/edit a published_feed, pick source calendars, show the subscribe
 * URL with a copy affordance, rotate the token. Feeds created via
 * quick-publish (CalendarsView) also appear here and are manageable the
 * same way - both flows build on the same published_feed mechanism.
 */
@Route(value = "feeds", layout = MainLayout.class)
@PageTitle("Published feeds | CalendarSync")
@PermitAll
public class PublishedFeedsView extends VerticalLayout {

    private final PublishedFeedService feedService;
    private final CalendarService calendarService;
    private final AppProperties appProperties;
    private final Grid<PublishedFeed> grid = new Grid<>(PublishedFeed.class, false);
    private final Paragraph empty = new Paragraph();

    public PublishedFeedsView(PublishedFeedService feedService, CalendarService calendarService,
                               AppProperties appProperties) {
        this.feedService = feedService;
        this.calendarService = calendarService;
        this.appProperties = appProperties;

        setSizeFull();

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_ROW_BORDERS);
        grid.addColumn(PublishedFeed::getName).setHeader("Name").setAutoWidth(true);
        grid.addColumn(this::sourceSummary).setHeader("Sources").setFlexGrow(1);
        grid.addColumn(f -> UiLabels.summary(ExportProfile.from(f))).setHeader("Export").setAutoWidth(true);
        grid.addColumn(f -> DisplayTime.format(f.getLastGeneratedAt()))
                .setHeader("Last generated").setAutoWidth(true);
        // The action column is the widest thing in the row; without a fixed
        // width the columns shared the space evenly and pushed "Delete" out
        // past the grid's right edge.
        grid.addComponentColumn(this::actionsFor).setHeader("").setFlexGrow(0).setAutoWidth(true);
        grid.setSizeFull();

        Button add = new Button("New feed", e -> openEditor(null));
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        empty.setText("No published feeds yet. Create one here, or publish a single calendar in one "
                + "click from the Calendars page.");

        add(new ViewHeader(VaadinIcon.RSS, "Published feeds",
                        "Filtered ICS feeds built from your synced calendars."),
                add, empty, grid);
        setFlexGrow(1, grid);
        refresh();
    }

    private HorizontalLayout actionsFor(PublishedFeed feed) {
        Button show = new Button("Subscribe URL",
                e -> SubscribeUrlDialog.show(feed.getName(), subscribeUrl(feed)));
        show.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button copy = new Button("Copy", e -> Clipboard.copy(this, subscribeUrl(feed), ok -> {
            if (ok) {
                Notification.show("URL copied").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } else {
                Notification.show("Couldn't reach the clipboard - open Subscribe URL and copy it there.",
                        6000, Notification.Position.MIDDLE);
            }
        }));
        copy.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button edit = new Button("Edit", e -> openEditor(feed));
        edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button rotate = new Button("Rotate token", e -> Confirm.destructive("Rotate this feed's token?",
                "The current subscribe URL stops working immediately. Every calendar app already "
                        + "subscribed to \"" + feed.getName() + "\" will need the new URL pasted in again.",
                "Rotate token",
                () -> {
                    feedService.rotateToken(feed.getId());
                    refresh();
                    Notification.show("Token rotated - the old URL no longer works");
                }));
        rotate.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button delete = new Button("Delete", e -> Confirm.destructive("Delete this feed?",
                "\"" + feed.getName() + "\" will stop being served and anyone subscribed to it will "
                        + "stop receiving events. This cannot be undone.",
                "Delete feed",
                () -> {
                    feedService.delete(feed.getId());
                    refresh();
                    Notification.show("Feed deleted");
                }));
        delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);

        return new HorizontalLayout(show, copy, edit, rotate, delete);
    }

    /**
     * Creates a feed, or edits an existing one's name and source calendars -
     * which previously could only be set at creation time, leaving rename and
     * "add another calendar to this feed" with no route through the UI at all.
     */
    private void openEditor(PublishedFeed feed) {
        boolean editing = feed != null;
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(editing ? "Edit feed" : "New published feed");
        dialog.setWidth("40em");
        dialog.setMaxWidth("95vw");

        TextField name = new TextField("Name");
        name.setWidthFull();
        name.setRequiredIndicatorVisible(true);

        List<CalendarSummary> available = calendarService.listForCurrentUser();
        MultiSelectComboBox<CalendarSummary> calendars = new MultiSelectComboBox<>("Source calendars");
        calendars.setWidthFull();
        calendars.setRequiredIndicatorVisible(true);
        calendars.setItems(available);
        calendars.setItemLabelGenerator(c -> c.connectionDisplayName() + " – " + c.calendarName());
        if (available.isEmpty()) {
            calendars.setEnabled(false);
            calendars.setHelperText("No calendars have synced yet - connect a provider first.");
        }

        ExportSettingsForm exportSettings = new ExportSettingsForm();

        if (editing) {
            name.setValue(feed.getName() == null ? "" : feed.getName());
            exportSettings.setProfile(ExportProfile.from(feed));
            Set<Long> current = feedService.sourcesFor(feed.getId()).stream()
                    .map(CalendarEntity::getId)
                    .collect(Collectors.toSet());
            calendars.setValue(available.stream()
                    .filter(c -> current.contains(c.calendarId()))
                    .collect(Collectors.toSet()));
        }

        Button save = new Button(editing ? "Save" : "Create", e -> {
            // Both problems are reported at once, and against the field that
            // has them, rather than as one notification naming neither.
            boolean valid = true;
            if (name.getValue() == null || name.getValue().isBlank()) {
                name.setInvalid(true);
                name.setErrorMessage("A feed needs a name");
                valid = false;
            } else {
                name.setInvalid(false);
            }
            if (calendars.getSelectedItems().isEmpty()) {
                calendars.setInvalid(true);
                calendars.setErrorMessage("Pick at least one source calendar");
                valid = false;
            } else {
                calendars.setInvalid(false);
            }
            if (!exportSettings.isValid()) {
                valid = false;
            }
            if (!valid) {
                return;
            }

            var calendarIds = calendars.getSelectedItems().stream().map(CalendarSummary::calendarId).toList();
            ExportProfile profile = exportSettings.getProfile();
            try {
                if (editing) {
                    feedService.update(feed.getId(), name.getValue().trim(), calendarIds, profile);
                } else {
                    feedService.create(name.getValue().trim(), calendarIds, profile);
                }
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            refresh();
            dialog.close();
            Notification.show(editing ? "Feed updated" : "Feed created");
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        VerticalLayout content = new VerticalLayout(name, calendars, exportSettings);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    /**
     * Names the feed's sources in the grid. A feed whose sources all went away
     * with a deleted connection still exists and is still served - as an empty
     * calendar - so it says so rather than showing a blank cell.
     */
    private String sourceSummary(PublishedFeed feed) {
        List<CalendarEntity> sources = feedService.sourcesFor(feed.getId());
        if (sources.isEmpty()) {
            return "No sources - this feed is empty";
        }
        return sources.stream().map(CalendarEntity::getName).collect(Collectors.joining(", "));
    }

    private String subscribeUrl(PublishedFeed feed) {
        return feedService.subscribeUrl(feed, appProperties.getBaseUrl());
    }

    private void refresh() {
        var feeds = feedService.listForCurrentUser();
        grid.setItems(feeds);
        grid.setVisible(!feeds.isEmpty());
        empty.setVisible(feeds.isEmpty());
    }
}
