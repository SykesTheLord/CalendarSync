package com.sykessec.calendarsync.ui.login;

import com.vaadin.flow.component.HtmlContainer;
import com.vaadin.flow.component.Tag;

/**
 * A real HTML &lt;form&gt;. flow-html-components has Input and NativeButton but no
 * form element, and this page needs a genuine browser form submit rather than a
 * Vaadin RPC call - the second factor is processed by a Spring Security filter,
 * exactly as the password step already is.
 */
@Tag("form")
public class NativeForm extends HtmlContainer {

    public NativeForm(String action) {
        getElement().setAttribute("method", "post");
        getElement().setAttribute("action", action);
    }
}
