package com.sykessec.calendarsync.ui.connections;

import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;

import java.util.List;
import java.util.function.Consumer;

/**
 * One place to add any provider. Each needs a genuinely different set of
 * details, so the form shows only the fields that provider uses rather than
 * one union of every field with a note explaining which ones to ignore.
 * auth_type is derived from the provider rather than typed - it records how
 * the stored credential is shaped ("url", "basic", "app_password"), which is
 * a consequence of the choice above it, not an independent decision.
 *
 * Google and Microsoft 365 are in the same dropdown even though they're set
 * up by an OAuth redirect rather than by typing credentials: picking one
 * turns the save button into "continue to sign-in" and hands off to
 * onOAuthConnect, since the connection those flows create can only be built
 * from what the provider hands back at the callback.
 */
public class ConnectionForm extends VerticalLayout {

    public record ConnectionInput(ProviderType provider, String displayName, String authType,
                                   String credentials, String caldavBaseUrl) {
    }

    /**
     * What an edit starts from. The password is absent on purpose - it is
     * never sent back to the browser, so leaving the box blank means "keep
     * the stored one" (see CalendarConnectionService.update).
     */
    public record Existing(ProviderType provider, String displayName, String caldavBaseUrl, String username) {
    }

    private static final List<ProviderType> ADDABLE_PROVIDERS =
            List.of(ProviderType.GOOGLE, ProviderType.MS_GRAPH, ProviderType.ICLOUD,
                    ProviderType.CALDAV, ProviderType.ICS_SOURCE);

    private final ComboBox<ProviderType> provider = new ComboBox<>("Provider");
    private final TextField displayName = new TextField("Display name");
    private final TextField url = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Paragraph hint = new Paragraph();
    private final FormLayout form = new FormLayout();
    private final Button save = new Button();
    private final HorizontalLayout actions;
    private final Existing existing;
    private final Consumer<ProviderType> onOAuthConnect;

    /** Adds a connection: typed-in providers via onSave, OAuth ones via onOAuthConnect. */
    public ConnectionForm(Consumer<ConnectionInput> onSave, Consumer<ProviderType> onOAuthConnect,
                           Runnable onCancel) {
        this(null, onSave, onOAuthConnect, onCancel);
    }

    /** Edits an existing connection - an OAuth one can only be renamed here. */
    public ConnectionForm(Existing existing, Consumer<ConnectionInput> onSave, Runnable onCancel) {
        this(existing, onSave, null, onCancel);
    }

    private ConnectionForm(Existing existing, Consumer<ConnectionInput> onSave,
                            Consumer<ProviderType> onOAuthConnect, Runnable onCancel) {
        this.existing = existing;
        this.onOAuthConnect = onOAuthConnect;
        boolean editing = existing != null;

        provider.setItems(editing ? List.of(existing.provider()) : ADDABLE_PROVIDERS);
        provider.setItemLabelGenerator(ConnectionForm::labelFor);
        provider.setRequiredIndicatorVisible(true);
        provider.setHelperText(editing
                ? "The provider can't be changed - delete the connection and add a new one to switch."
                : null);
        provider.addValueChangeListener(e -> showFieldsFor(e.getValue()));

        displayName.setRequiredIndicatorVisible(true);
        displayName.setHelperText("What you'll call this connection in CalendarSync.");
        url.setRequiredIndicatorVisible(true);
        username.setRequiredIndicatorVisible(true);
        password.setRequiredIndicatorVisible(!editing);

        form.add(provider, displayName, url, username, password);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("30em", 2));
        if (editing) {
            provider.setValue(existing.provider());
            provider.setReadOnly(true);
            displayName.setValue(existing.displayName() == null ? "" : existing.displayName());
            url.setValue(existing.caldavBaseUrl() == null ? "" : existing.caldavBaseUrl());
            username.setValue(existing.username() == null ? "" : existing.username());
            showFieldsFor(existing.provider());
        } else {
            showFieldsFor(null);
        }

        save.addClickListener(e -> submit(onSave));
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", e -> onCancel.run());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        actions = new HorizontalLayout(save, cancel);

        setPadding(false);
        add(form, hint);
    }

    /**
     * The save/cancel pair, so the dialog hosting this form can put it in its
     * own footer rather than at the bottom of the scrolling content.
     */
    public HorizontalLayout actions() {
        return actions;
    }

    private static String labelFor(ProviderType type) {
        return com.sykessec.calendarsync.ui.UiLabels.of(type);
    }

    /**
     * iCloud needs no URL (discovery always starts at caldav.icloud.com and
     * follows the redirect to whichever partition host the account lives on),
     * and an ICS feed needs no credentials - it's a plain public URL fetched
     * read-only. Google and Microsoft 365 need nothing typed at all: adding
     * one is a redirect, and the display name only becomes editable once the
     * callback has created the connection.
     */
    private void showFieldsFor(ProviderType type) {
        boolean chosen = type != null;
        boolean handOff = isOAuth(type) && existing == null;

        // A different provider asks for different fields, so errors raised
        // against the previous one no longer describe anything on screen.
        displayName.setInvalid(false);
        url.setInvalid(false);
        username.setInvalid(false);
        password.setInvalid(false);
        displayName.setVisible(chosen && !handOff);
        url.setVisible(type == ProviderType.ICS_SOURCE || type == ProviderType.CALDAV);
        username.setVisible(type == ProviderType.CALDAV || type == ProviderType.ICLOUD);
        password.setVisible(type == ProviderType.CALDAV || type == ProviderType.ICLOUD);
        hint.setVisible(chosen);
        save.setText(handOff ? "Continue to " + labelFor(type) : "Save");

        if (!chosen) {
            hint.setText("");
            return;
        }

        switch (type) {
            case ICS_SOURCE -> {
                url.setLabel("ICS feed URL");
                url.setPlaceholder("https://example.com/calendar.ics");
                url.setHelperText("The public .ics URL to subscribe to.");
                hint.setText("Read-only. Events from an ICS feed are never deleted at the source - "
                        + "filtering one means excluding events from a published feed you build on it.");
            }
            case CALDAV -> {
                url.setLabel("CalDAV server URL");
                url.setPlaceholder("https://calendar.example.com/dav/");
                url.setHelperText("The server's CalDAV entry point. Discovery finds your calendars from there.");
                username.setLabel("Username");
                username.setHelperText(null);
                password.setLabel("Password");
                password.setHelperText(keepPasswordHint());
                hint.setText("Credentials are sent as HTTP Basic auth, so prefer an https:// URL.");
            }
            case ICLOUD -> {
                username.setLabel("Apple ID");
                username.setHelperText("The email address you sign in to iCloud with.");
                password.setLabel("App-specific password");
                password.setHelperText(existing == null
                        ? "Generate one at appleid.apple.com - never your Apple ID password."
                        : keepPasswordHint());
                hint.setText("No URL needed: iCloud discovery starts at caldav.icloud.com and follows "
                        + "the redirect to your account's partition host automatically.");
            }
            case GOOGLE, MS_GRAPH -> hint.setText(handOff
                    ? "Nothing to fill in: you'll be sent to " + labelFor(type) + " to sign in and "
                            + "approve access, and the connection is created when you come back. "
                            + "You can rename it afterwards."
                    : "Connected through " + labelFor(type) + " sign-in. Only the display name is "
                            + "editable here - to re-authorize, delete the connection and add it again.");
        }
    }

    private static boolean isOAuth(ProviderType type) {
        return type == ProviderType.GOOGLE || type == ProviderType.MS_GRAPH;
    }

    private String keepPasswordHint() {
        return existing == null ? null : "Leave blank to keep the stored password.";
    }

    private void submit(Consumer<ConnectionInput> onSave) {
        ProviderType type = provider.getValue();
        if (type == null) {
            provider.setInvalid(true);
            provider.setErrorMessage("Pick a provider first");
            return;
        }
        provider.setInvalid(false);

        // Adding a Google or Microsoft connection is a redirect, not a save:
        // the credential only exists once the provider redirects back.
        if (isOAuth(type) && existing == null) {
            onOAuthConnect.accept(type);
            return;
        }

        // Every check runs before anything returns, so one Save marks every
        // field that needs attention. Reporting only the first meant an empty
        // CalDAV form asked for the name, then the URL, then the username,
        // then the password - four rounds to learn four things.
        boolean valid = required(displayName, "Give this connection a name");

        if (url.isVisible()) {
            valid &= required(url, "This is required") && validUrl(url);
        }
        if (username.isVisible()) {
            valid &= required(username, "This is required");
            // CalDavProvider splits the stored credential on the first ':', so
            // a password containing colons survives round-tripping but a
            // username containing one would not.
            if (username.getValue() != null && username.getValue().contains(":")) {
                username.setInvalid(true);
                username.setErrorMessage("A username can't contain a colon");
                valid = false;
            }
        }

        // While editing, a blank password means "keep the stored one" - but
        // the credential is stored as a single "username:password" string, so
        // a changed username can only be written together with its password.
        boolean keepPassword = existing != null
                && (password.getValue() == null || password.getValue().isBlank());
        if (keepPassword && username.isVisible() && !username.getValue().trim().equals(storedUsername())) {
            password.setInvalid(true);
            password.setErrorMessage("Re-enter the password to change the username");
            valid = false;
        } else if (!keepPassword && password.isVisible()) {
            valid &= required(password, "This is required");
        } else {
            password.setInvalid(false);
        }

        if (!valid) {
            return;
        }

        String credentials = username.isVisible() && !keepPassword
                ? username.getValue().trim() + ":" + password.getValue()
                : null;
        String baseUrl = url.isVisible() ? url.getValue().trim() : null;

        onSave.accept(new ConnectionInput(type, displayName.getValue().trim(), authTypeFor(type),
                credentials, baseUrl));
    }

    private String storedUsername() {
        String stored = existing == null ? null : existing.username();
        return stored == null ? "" : stored;
    }

    private static String authTypeFor(ProviderType type) {
        return switch (type) {
            case ICS_SOURCE -> "url";
            case CALDAV -> "basic";
            case ICLOUD -> "app_password";
            default -> "oauth2";
        };
    }

    private boolean required(com.vaadin.flow.component.textfield.TextFieldBase<?, String> field, String message) {
        String value = field.getValue();
        if (value == null || value.isBlank()) {
            field.setInvalid(true);
            field.setErrorMessage(message);
            return false;
        }
        field.setInvalid(false);
        return true;
    }

    private boolean validUrl(TextField field) {
        String value = field.getValue().trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            field.setInvalid(true);
            field.setErrorMessage("Must start with http:// or https://");
            return false;
        }
        try {
            java.net.URI.create(value);
        } catch (IllegalArgumentException e) {
            field.setInvalid(true);
            field.setErrorMessage("That isn't a valid URL");
            return false;
        }
        field.setInvalid(false);
        return true;
    }
}
