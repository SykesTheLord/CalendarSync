package com.sykessec.calendarsync.ui.connections;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.service.CalendarConnectionService;
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
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import jakarta.annotation.security.PermitAll;

/**
 * Also answers at "" (the app's bare root): with no route mapped there,
 * Spring Security's saved-request replay - which fires for anyone who opens
 * http://host:port/ while logged out, since that's the single most natural
 * URL to visit - has nowhere valid to send them back to after login and
 * 403s instead. See SecurityConfig's defaultSuccessUrl for the sibling case
 * (direct login with no prior navigation attempt).
 */
@Route(value = "connections", layout = MainLayout.class)
@RouteAlias(value = "", layout = MainLayout.class)
@PageTitle("Connections | CalendarSync")
@PermitAll
public class ConnectionsView extends VerticalLayout implements BeforeEnterObserver {

    private final CalendarConnectionService connectionService;
    private final Grid<CalendarConnection> grid = new Grid<>(CalendarConnection.class, false);
    private final Paragraph error = new Paragraph();

    public ConnectionsView(CalendarConnectionService connectionService) {
        this.connectionService = connectionService;

        setSizeFull();

        error.getStyle().set("color", "var(--lumo-error-text-color)");
        error.setVisible(false);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_ROW_BORDERS);
        grid.addColumn(c -> UiLabels.of(c.getProvider())).setHeader("Provider").setAutoWidth(true);
        grid.addColumn(CalendarConnection::getDisplayName).setHeader("Display name").setAutoWidth(true);
        grid.addColumn(c -> UiLabels.authType(c.getAuthType())).setHeader("Auth type").setAutoWidth(true);
        // Populated for ICS feeds and CalDAV servers; iCloud and the OAuth
        // providers discover their own endpoints, so it stays blank for those.
        //
        // Shown host-and-path only: a subscribe URL routinely carries its own
        // access token in the query string (Moodle, Google's secret address,
        // Outlook's published calendars), and a credential does not belong in
        // a column that is on screen whenever this page is.
        grid.addColumn(c -> safeUrl(c.getCaldavBaseUrl())).setHeader("URL").setFlexGrow(1);
        grid.addComponentColumn(connection -> {
            Button edit = new Button("Edit", e -> openEditForm(connection));
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            Button delete = new Button("Delete", e -> confirmDelete(connection));
            delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            return new HorizontalLayout(edit, delete);
        }).setHeader("").setFlexGrow(0).setAutoWidth(true);

        Button add = new Button("Add connection", e -> openForm());
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        // Grid defaults to a fixed 400px when nothing sizes it, which left a
        // half-empty box floating above a page of blank space. Letting it grow
        // into the space the view already has is what makes the list usable
        // when it is longer than a screen.
        grid.setSizeFull();
        add(new ViewHeader(VaadinIcon.PLUG, "Connections",
                        "Connect calendar providers and manage their credentials."),
                error, add, grid);
        setFlexGrow(1, grid);
        refresh();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Set on every entry, not only when the parameter is present: an OAuth
        // failure banner used to survive a successful retry because nothing
        // ever hid it again.
        String message = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("error", java.util.List.of())
                .stream().findFirst().orElse(null);
        error.setText(message == null ? "" : "Connection failed: " + message);
        error.setVisible(message != null);
    }

    /**
     * Keeps the origin and path so a connection is still recognisable, and
     * drops the query string, which is where feed tokens live.
     */
    private static String safeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query) + "?…";
    }

    private void confirmDelete(CalendarConnection connection) {
        Confirm.destructive("Delete connection?",
                "\"" + connection.getDisplayName() + "\" and every calendar synced through it will be "
                        + "removed, and any published feed built on those calendars will stop returning "
                        + "their events. This cannot be undone.",
                "Delete connection",
                () -> {
                    try {
                        connectionService.delete(connection.getId());
                    } catch (IllegalArgumentException ex) {
                        Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                                .addThemeVariants(NotificationVariant.LUMO_ERROR);
                        return;
                    }
                    refresh();
                    Notification.show("Connection deleted");
                });
    }

    private void openForm() {
        Dialog dialog = newFormDialog("Add connection");
        ConnectionForm form = new ConnectionForm(input -> {
            try {
                connectionService.create(input.provider(), input.displayName(), input.authType(),
                        input.credentials(), input.caldavBaseUrl());
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            refresh();
            dialog.close();
            Notification.show("Connection added");
        }, provider -> {
            // A full page load, not a router navigation: /oauth2/**/authorize
            // is a plain servlet endpoint that answers with a redirect off to
            // the provider, which the Vaadin router can't follow.
            dialog.close();
            getUI().ifPresent(ui -> ui.getPage().setLocation(authorizeUrlFor(provider)));
        }, dialog::close);
        addFormToDialog(dialog, form);
        dialog.open();
    }

    private static String authorizeUrlFor(ProviderType provider) {
        return switch (provider) {
            case GOOGLE -> "/oauth2/google/authorize";
            case MS_GRAPH -> "/oauth2/microsoft/authorize";
            default -> throw new IllegalArgumentException("Not an OAuth provider: " + provider);
        };
    }

    private void openEditForm(CalendarConnection connection) {
        Dialog dialog = newFormDialog("Edit connection");
        ConnectionForm.Existing existing = new ConnectionForm.Existing(connection.getProvider(),
                connection.getDisplayName(), connection.getCaldavBaseUrl(),
                connectionService.credentialUsername(connection.getId()));
        ConnectionForm form = new ConnectionForm(existing, input -> {
            try {
                connectionService.update(connection.getId(), input.displayName(), input.credentials(),
                        input.caldavBaseUrl());
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            refresh();
            dialog.close();
            Notification.show("Connection updated");
        }, dialog::close);
        addFormToDialog(dialog, form);
        dialog.open();
    }

    /** Buttons belong in the dialog's own footer so they stay put when the form scrolls. */
    private static void addFormToDialog(Dialog dialog, ConnectionForm form) {
        dialog.add(form);
        dialog.getFooter().add(form.actions());
    }

    private Dialog newFormDialog(String title) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("36em");
        dialog.setMaxWidth("95vw");
        return dialog;
    }

    private void refresh() {
        grid.setItems(connectionService.listForCurrentUser());
    }
}
