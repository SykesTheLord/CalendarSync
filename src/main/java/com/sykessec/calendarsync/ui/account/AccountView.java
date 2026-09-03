package com.sykessec.calendarsync.ui.account;

import com.sykessec.calendarsync.security.CurrentUser;
import com.sykessec.calendarsync.service.UserAdminService;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * The first-run admin account is created with a generated password that the
 * log tells the user to change - which, until this view existed, there was no
 * way to do: the only code path that ever set a password hash was account
 * creation. Any logged-in user can change their own password here; an admin
 * resetting someone else's does it from AdminUserView instead.
 */
@Route(value = "account", layout = MainLayout.class)
@PageTitle("Account | CalendarSync")
@PermitAll
public class AccountView extends VerticalLayout {

    public AccountView(UserAdminService userAdminService, CurrentUser currentUser) {
        PasswordField current = new PasswordField("Current password");
        PasswordField updated = new PasswordField("New password");
        updated.setHelperText("At least " + UserAdminService.MIN_PASSWORD_LENGTH + " characters.");
        PasswordField confirm = new PasswordField("Confirm new password");

        for (PasswordField field : new PasswordField[] { current, updated, confirm }) {
            field.setRequiredIndicatorVisible(true);
            field.setWidth("22em");
        }

        Button save = new Button("Change password", e -> {
            if (!updated.getValue().equals(confirm.getValue())) {
                confirm.setInvalid(true);
                confirm.setErrorMessage("The two new passwords don't match");
                return;
            }
            confirm.setInvalid(false);
            try {
                userAdminService.changeOwnPassword(currentUser.id(), current.getValue(), updated.getValue());
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            current.clear();
            updated.clear();
            confirm.clear();
            Notification.show("Password changed").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        VerticalLayout card = new VerticalLayout(current, updated, confirm, save);
        card.addClassName("cs-card");
        card.setWidth(null);

        add(new ViewHeader(VaadinIcon.USER, "Account",
                        "Signed in as " + currentUser.principal().getUsername() + "."),
                new Paragraph("Changing your password does not sign you out of this session."),
                card);
    }
}
