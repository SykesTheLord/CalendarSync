package com.sykessec.calendarsync.ui.calendars;

import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.service.CalendarService;
import com.sykessec.calendarsync.service.CalendarSummary;
import com.sykessec.calendarsync.service.PublishedFeedService;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.SubscribeUrlDialog;
import com.sykessec.calendarsync.ui.UiLabels;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * A flattened list of every calendar synced across the user's connections
 * (not nested under each connection), populated by the sync job's calendar
 * discovery. "Publish as ICS Feed" is the quick-publish fast path: reuses
 * an existing single-source feed if one already exists for that calendar,
 * otherwise creates a new unfiltered mirror - rules can be attached to the
 * result afterward via the Rules view exactly like any other feed.
 */
@Route(value = "calendars", layout = MainLayout.class)
@PageTitle("Calendars | CalendarSync")
@PermitAll
public class CalendarsView extends VerticalLayout {

    private final CalendarService calendarService;
    private final PublishedFeedService feedService;
    private final AppProperties appProperties;
    private final Grid<CalendarSummary> grid = new Grid<>(CalendarSummary.class, false);
    private final Paragraph empty = new Paragraph();

    public CalendarsView(CalendarService calendarService, PublishedFeedService feedService,
                          AppProperties appProperties) {
        this.calendarService = calendarService;
        this.feedService = feedService;
        this.appProperties = appProperties;

        setSizeFull();

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_ROW_BORDERS);
        grid.addColumn(s -> UiLabels.of(s.provider())).setHeader("Provider").setAutoWidth(true);
        grid.addColumn(CalendarSummary::connectionDisplayName).setHeader("Connection").setAutoWidth(true);
        grid.addColumn(CalendarSummary::calendarName).setHeader("Calendar").setFlexGrow(1);
        grid.addColumn(s -> s.writable() ? "Read/write" : "Read-only").setHeader("Access").setAutoWidth(true);
        grid.addComponentColumn(summary -> new Button("Publish as ICS Feed", e -> publish(summary)))
                .setHeader("").setFlexGrow(0).setAutoWidth(true);
        grid.setSizeFull();

        empty.setText("No calendars synced yet - connect a provider on the Connections "
                + "page. Calendars appear here automatically the first time that connection's sync job runs.");

        add(new ViewHeader(VaadinIcon.CALENDAR, "Calendars",
                        "Every calendar synced across your connections."),
                empty, grid);
        setFlexGrow(1, grid);
        refresh();
    }

    /**
     * The URL goes into a dialog rather than a notification: it's the whole
     * point of the click, it's too long to read in the eight seconds a
     * notification lasts, and it has to be copyable.
     */
    private void publish(CalendarSummary summary) {
        try {
            var feed = feedService.quickPublish(summary.calendarId());
            SubscribeUrlDialog.show("Published \"" + summary.calendarName() + "\"",
                    feedService.subscribeUrl(feed, appProperties.getBaseUrl()));
        } catch (IllegalArgumentException ex) {
            Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void refresh() {
        var calendars = calendarService.listForCurrentUser();
        grid.setItems(calendars);
        // The grid and the empty-state message are alternatives: only the
        // grid used to be toggled, so "No calendars synced yet" stayed on
        // screen above a table full of synced calendars.
        grid.setVisible(!calendars.isEmpty());
        empty.setVisible(calendars.isEmpty());
    }
}
