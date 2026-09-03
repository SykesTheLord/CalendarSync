package com.sykessec.calendarsync.ui;

import com.vaadin.flow.component.confirmdialog.ConfirmDialog;

/**
 * One confirmation step in front of the actions that can't be undone from the
 * UI. Deleting a connection, a rule or a feed, and rotating a feed's token,
 * were all single-click and irreversible - rotation in particular breaks every
 * calendar client already subscribed, and only said so afterwards.
 *
 * The cancel button is the one focused by default, so Enter on a dialog the
 * user didn't read does nothing.
 */
public final class Confirm {

    private Confirm() {
    }

    public static void destructive(String title, String message, String confirmText, Runnable onConfirm) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(title);
        dialog.setText(message);
        dialog.setCancelable(true);
        dialog.setCancelText("Cancel");
        dialog.setConfirmText(confirmText);
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> onConfirm.run());
        dialog.open();
    }
}
