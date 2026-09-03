package com.sykessec.calendarsync.ui;

import com.vaadin.flow.component.Component;

import java.util.function.Consumer;

/**
 * Copy-to-clipboard that reports whether it actually worked.
 *
 * navigator.clipboard only exists in a secure context, so on a plain-http
 * deployment (any LAN address that isn't localhost) the previous
 * "navigator.clipboard && navigator.clipboard.writeText(...)" short-circuited
 * to undefined and the caller still announced "URL copied". This tries the
 * modern API first, falls back to the execCommand path that does work over
 * http, and hands the caller a boolean either way so the message it shows can
 * be true.
 */
public final class Clipboard {

    private static final String COPY_JS = """
            var text = $0;
            try {
              if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(text);
                return true;
              }
            } catch (e) {
              // fall through to the execCommand path below
            }
            try {
              var area = document.createElement('textarea');
              area.value = text;
              area.setAttribute('readonly', '');
              area.style.position = 'fixed';
              area.style.top = '0';
              area.style.opacity = '0';
              document.body.appendChild(area);
              area.select();
              var ok = document.execCommand('copy');
              document.body.removeChild(area);
              return ok === true;
            } catch (e) {
              return false;
            }
            """;

    private Clipboard() {
    }

    public static void copy(Component context, String text, Consumer<Boolean> onResult) {
        context.getElement().executeJs(COPY_JS, text).then(Boolean.class, onResult::accept);
    }
}
