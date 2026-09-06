package com.sykessec.calendarsync.ui.account;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.security.CurrentUser;
import com.sykessec.calendarsync.service.TwoFactorService;
import com.sykessec.calendarsync.ui.Clipboard;
import com.sykessec.calendarsync.ui.Confirm;
import com.sykessec.calendarsync.ui.MainLayout;
import com.sykessec.calendarsync.ui.ViewHeader;
import com.sykessec.calendarsync.util.Totp;
import com.sykessec.calendarsync.util.TotpQrCode;
import com.sykessec.calendarsync.util.TotpUri;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import jakarta.annotation.security.PermitAll;

import java.util.List;

/**
 * Enrolment, recovery codes and switching the second factor off.
 *
 * The candidate secret lives in the Vaadin session until a code proves the
 * user's authenticator actually has it. Writing it to app_user first and
 * flipping an "enabled" flag afterwards would be simpler, but it means a
 * mis-scanned QR or a closed tab leaves a row that looks half-enrolled - and
 * the failure mode for getting second-factor state wrong is somebody locked
 * out of their own account with no way back except an administrator.
 */
@Route(value = "account/two-factor", layout = MainLayout.class)
@PageTitle("Two-factor authentication | CalendarSync")
@PermitAll
public class TwoFactorSetupView extends VerticalLayout {

    /** Vaadin session key for the not-yet-confirmed secret. */
    private static final String CANDIDATE_SECRET = "cs-totp-candidate-secret";

    private final TwoFactorService twoFactorService;
    private final AppUserRepository appUserRepository;
    private final CurrentUser currentUser;

    public TwoFactorSetupView(TwoFactorService twoFactorService,
                              AppUserRepository appUserRepository,
                              CurrentUser currentUser) {
        this.twoFactorService = twoFactorService;
        this.appUserRepository = appUserRepository;
        this.currentUser = currentUser;

        add(new ViewHeader(VaadinIcon.SHIELD, "Two-factor authentication",
                "A code from your phone or password manager, in addition to your password."));
        render();
    }

    private void render() {
        getChildren().filter(c -> !(c instanceof ViewHeader)).toList().forEach(this::remove);
        AppUser user = user();
        add(user.isTotpEnabled() ? enrolledCard(user) : enrolmentCard(user));
    }

    // --- not yet enrolled -------------------------------------------------

    private VerticalLayout enrolmentCard(AppUser user) {
        String secret = candidateSecret();

        VerticalLayout card = card();
        card.add(new Paragraph("Scan this with your authenticator app or password manager, "
                + "then enter the six-digit code it shows to finish."));

        Image qr = new Image(TotpQrCode.asDataUri(TotpUri.build(user.getUsername(), secret)),
                "Two-factor enrolment QR code");
        qr.setWidth("220px");
        qr.setHeight("220px");
        card.add(qr);

        card.add(manualEntry(secret));

        TextField code = new TextField("Six-digit code");
        code.setWidth("12em");
        code.setRequiredIndicatorVisible(true);
        code.setHelperText("From your authenticator, not from an email or text message.");
        // A native one-time-code hint here too, so a password manager that has
        // just stored the secret can offer the code straight back.
        code.getElement().setAttribute("autocomplete", "one-time-code");

        Button confirm = new Button("Turn on two-factor authentication", e -> {
            code.setInvalid(false);
            if (code.getValue() == null || code.getValue().isBlank()) {
                code.setInvalid(true);
                code.setErrorMessage("Enter the code from your authenticator");
                return;
            }
            try {
                List<String> codes = twoFactorService.enable(user.getId(), secret, code.getValue());
                VaadinSession.getCurrent().setAttribute(CANDIDATE_SECRET, null);
                showRecoveryCodes(codes, "Two-factor authentication is on.");
            } catch (IllegalArgumentException ex) {
                code.setInvalid(true);
                code.setErrorMessage(ex.getMessage());
            }
        });
        confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        card.add(code, confirm);
        return card;
    }

    /**
     * The secret as text, because a great many people enrol on the same device
     * they are browsing on and cannot point a camera at their own screen.
     */
    private VerticalLayout manualEntry(String secret) {
        Span key = new Span(TotpUri.formatSecretForDisplay(secret));
        key.getStyle().set("font-family", "var(--lumo-font-family-monospace)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "var(--lumo-space-s)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("word-break", "break-all");

        Button copy = new Button("Copy", e -> Clipboard.copy(this, secret, ok -> Notification
                .show(Boolean.TRUE.equals(ok) ? "Setup key copied" : "Could not copy - select it by hand")));
        copy.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        VerticalLayout block = new VerticalLayout(
                new Paragraph("Can't scan? Enter this setup key manually instead:"),
                key, copy);
        block.setPadding(false);
        block.setSpacing(false);
        return block;
    }

    /**
     * Held in the session so that a page refresh does not silently swap the
     * secret under a QR the user has already scanned - they would enrol the old
     * one and every code would be rejected.
     */
    private String candidateSecret() {
        Object existing = VaadinSession.getCurrent().getAttribute(CANDIDATE_SECRET);
        if (existing instanceof String secret && !secret.isBlank()) {
            return secret;
        }
        String secret = Totp.generateSecret();
        VaadinSession.getCurrent().setAttribute(CANDIDATE_SECRET, secret);
        return secret;
    }

    // --- already enrolled -------------------------------------------------

    private VerticalLayout enrolledCard(AppUser user) {
        VerticalLayout card = card();
        card.add(new H3("Two-factor authentication is on"));
        card.add(new Paragraph("Switched on " + user.getTotpConfirmedAt() + " (UTC). "
                + twoFactorService.remainingRecoveryCodes(user.getId())
                + " of " + TwoFactorService.RECOVERY_CODE_COUNT + " recovery codes remain."));

        PasswordField password = new PasswordField("Your password");
        password.setWidth("22em");
        password.setHelperText("Confirms it is you making the change.");

        Button regenerate = new Button("Generate new recovery codes", e -> {
            try {
                List<String> codes = twoFactorService.regenerateRecoveryCodes(user.getId(), password.getValue());
                showRecoveryCodes(codes, "New recovery codes generated. The old ones no longer work.");
            } catch (IllegalArgumentException ex) {
                error(ex);
            }
        });
        regenerate.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        card.add(password, regenerate);

        if (user.isTotpRequired()) {
            Paragraph required = new Paragraph(
                    "An administrator has made two-factor authentication mandatory for this account, "
                            + "so it cannot be switched off here.");
            required.getStyle().set("color", "var(--lumo-secondary-text-color)");
            card.add(required);
        } else {
            Button disable = new Button("Turn off two-factor authentication", e -> Confirm.destructive(
                    "Turn off two-factor authentication?",
                    "Your account will be protected by its password alone. "
                            + "Your recovery codes will stop working.",
                    "Turn it off",
                    () -> {
                        try {
                            twoFactorService.disable(user.getId(), password.getValue());
                            Notification.show("Two-factor authentication is off")
                                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                            render();
                        } catch (IllegalArgumentException ex) {
                            error(ex);
                        }
                    }));
            disable.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            card.add(disable);
        }
        return card;
    }

    // --- recovery codes ---------------------------------------------------

    /**
     * Shown exactly once. They are stored hashed, so this screen is genuinely
     * the only opportunity - which is why it replaces the view rather than
     * appearing in a dialog that a stray click dismisses.
     */
    private void showRecoveryCodes(List<String> codes, String heading) {
        getChildren().filter(c -> !(c instanceof ViewHeader)).toList().forEach(this::remove);

        VerticalLayout card = card();
        card.add(new H3(heading));
        card.add(new Paragraph("Save these recovery codes somewhere safe and separate from your phone. "
                + "Each one works once, and this is the only time they will be shown."));

        VerticalLayout list = new VerticalLayout();
        list.setPadding(false);
        list.setSpacing(false);
        list.getStyle().set("font-family", "var(--lumo-font-family-monospace)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("padding", "var(--lumo-space-m)")
                .set("border-radius", "var(--lumo-border-radius-m)");
        codes.forEach(code -> list.add(new Span(code)));
        card.add(list);

        String joined = String.join(System.lineSeparator(), codes);
        Button copy = new Button("Copy all", e -> Clipboard.copy(this, joined, ok -> Notification
                .show(Boolean.TRUE.equals(ok) ? "Recovery codes copied" : "Could not copy - select them by hand")));
        copy.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button done = new Button("I've saved them", e -> render());
        done.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        card.add(new HorizontalLayout(copy, done));
        add(card);
    }

    // --- helpers ----------------------------------------------------------

    private VerticalLayout card() {
        VerticalLayout card = new VerticalLayout();
        card.addClassName("cs-card");
        card.setWidth(null);
        return card;
    }

    private AppUser user() {
        return appUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new IllegalStateException("Signed-in user no longer exists"));
    }

    private void error(IllegalArgumentException ex) {
        Notification.show(ex.getMessage(), 6000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
