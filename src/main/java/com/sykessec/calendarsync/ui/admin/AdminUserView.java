package com.sykessec.calendarsync.ui.admin;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.security.CurrentUser;
import com.sykessec.calendarsync.service.UserAdminService;
import com.sykessec.calendarsync.ui.Confirm;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.UiLabels;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route(value = "admin", layout = MainLayout.class)
@PageTitle("Admin | CalendarSync")
@RolesAllowed("ADMIN")
public class AdminUserView extends VerticalLayout {

    private final UserAdminService userAdminService;
    private final CurrentUser currentUser;
    private final Grid<AppUser> grid = new Grid<>(AppUser.class, false);

    public AdminUserView(UserAdminService userAdminService, CurrentUser currentUser) {
        this.userAdminService = userAdminService;
        this.currentUser = currentUser;

        setSizeFull();

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_ROW_BORDERS);
        grid.addColumn(AppUser::getUsername).setHeader("Username").setFlexGrow(1);
        grid.addColumn(u -> UiLabels.of(u.getRole())).setHeader("Role").setAutoWidth(true);
        grid.addColumn(u -> UiLabels.yesNo(u.isEnabled())).setHeader("Enabled").setAutoWidth(true);
        grid.addComponentColumn(this::actionsFor).setHeader("").setFlexGrow(0).setAutoWidth(true);
        grid.setSizeFull();

        add(new ViewHeader(VaadinIcon.USERS, "Admin", "Create and manage user accounts."),
                new Paragraph("Administrators can manage accounts here. Administrators do NOT have "
                        + "access to other users' calendars, rules, published feeds, or deletion history - "
                        + "account management and data access are separate concerns in this application."),
                createUserRow(),
                grid);
        setFlexGrow(1, grid);
        refresh();
    }

    /**
     * The inputs carry labels and the button doesn't, so the row is aligned on
     * its baseline rather than stretched - the button used to float above the
     * fields. It also wraps: at phone width the unwrapped row pushed the role
     * picker and the button off-screen entirely, which left no way to create a
     * user at all.
     */
    private HorizontalLayout createUserRow() {
        TextField username = new TextField("Username");
        PasswordField password = new PasswordField("Password");
        password.setHelperText("At least " + UserAdminService.MIN_PASSWORD_LENGTH + " characters.");
        ComboBox<Role> role = new ComboBox<>("Role");
        role.setItems(Role.values());
        role.setItemLabelGenerator(UiLabels::of);
        role.setValue(Role.USER);

        Button create = new Button("Create user", e -> {
            if (!run(() -> userAdminService.createUser(username.getValue(), password.getValue(), role.getValue()))) {
                return;
            }
            username.clear();
            password.clear();
            role.setValue(Role.USER);
            refresh();
            Notification.show("User created").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout row = new HorizontalLayout(username, password, role, create);
        row.setAlignItems(Alignment.BASELINE);
        row.getStyle().set("flex-wrap", "wrap");
        return row;
    }

    private HorizontalLayout actionsFor(AppUser user) {
        boolean self = user.getId().equals(currentUser.id());

        Button toggle = new Button(user.isEnabled() ? "Disable" : "Enable", e -> {
            if (!user.isEnabled()) {
                applyEnabled(user, true);
                return;
            }
            // Disabling yourself is allowed as long as another admin remains -
            // the service enforces that - but it locks you out at the next
            // login, which is worth being asked about first.
            Confirm.destructive(self ? "Disable your own account?" : "Disable this account?",
                    self ? "You will not be able to sign in again unless another administrator "
                            + "re-enables your account."
                            : "\"" + user.getUsername() + "\" will not be able to sign in. Their calendars, "
                                    + "rules and feeds are kept, and syncing continues.",
                    "Disable",
                    () -> applyEnabled(user, false));
        });
        toggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button reset = new Button("Reset password", e -> openResetDialog(user));
        reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        return new HorizontalLayout(toggle, reset);
    }

    private void applyEnabled(AppUser user, boolean enabled) {
        if (run(() -> userAdminService.setEnabled(user.getId(), enabled))) {
            refresh();
            Notification.show(enabled ? "Account enabled" : "Account disabled");
        }
    }

    private void openResetDialog(AppUser user) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Reset password for " + user.getUsername());
        dialog.setWidth("28em");
        dialog.setMaxWidth("95vw");

        PasswordField newPassword = new PasswordField("New password");
        newPassword.setHelperText("At least " + UserAdminService.MIN_PASSWORD_LENGTH
                + " characters. You'll need to give it to them yourself - it isn't shown again.");
        newPassword.setWidthFull();

        Button save = new Button("Reset", e -> {
            if (run(() -> userAdminService.resetPassword(user.getId(), newPassword.getValue()))) {
                dialog.close();
                Notification.show("Password reset for " + user.getUsername())
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.add(newPassword);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    /**
     * These service calls reject bad input by throwing, and the message is
     * written for the person who typed it - shown as a notification rather
     * than escaping the click listener into Vaadin's generic internal-error
     * overlay, which is what used to happen for something as ordinary as a
     * username that was already taken.
     */
    private boolean run(Runnable action) {
        try {
            action.run();
            return true;
        } catch (IllegalArgumentException e) {
            Notification.show(e.getMessage(), 6000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return false;
        }
    }

    private void refresh() {
        grid.setItems(userAdminService.listAll());
    }
}
