package com.sykessec.calendarsync.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Shows a feed's subscribe URL somewhere it can be read and selected.
 *
 * Quick-publish used to put the URL in an 8-second notification and the feeds
 * grid truncated it to "http://localhost:8080/feed/D0mCi…" - in both places
 * the one thing the user came for was the part they couldn't get at. A copy
 * button is offered, but the field is there so the URL is still obtainable
 * when the clipboard isn't.
 */
public final class SubscribeUrlDialog {

    private SubscribeUrlDialog() {
    }

    public static void show(String title, String url) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("38em");
        dialog.setMaxWidth("95vw");

        TextField field = new TextField("Subscribe URL");
        field.setValue(url);
        field.setReadOnly(true);
        field.setWidthFull();

        Paragraph note = new Paragraph("Anyone with this URL can read the feed - it is the only thing "
                + "protecting it. Rotate the token from the Published feeds page if it leaks.");
        note.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");

        VerticalLayout content = new VerticalLayout(field, note);
        content.setPadding(false);
        dialog.add(content);

        Button copy = new Button("Copy URL", e -> Clipboard.copy(dialog, url, ok -> {
            if (ok) {
                Notification.show("URL copied").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } else {
                Notification.show("Couldn't reach the clipboard - select the URL above and copy it manually.",
                        6000, Notification.Position.MIDDLE);
            }
        }));
        copy.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button close = new Button("Close", e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dialog.getFooter().add(close, copy);

        dialog.open();
    }
}
