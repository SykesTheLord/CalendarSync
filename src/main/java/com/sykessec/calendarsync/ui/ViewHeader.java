package com.sykessec.calendarsync.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** Consistent icon + title (+ optional description) banner at the top of every view. */
public class ViewHeader extends HorizontalLayout {

    public ViewHeader(VaadinIcon icon, String title, String description) {
        addClassName("cs-view-header");
        setAlignItems(Alignment.CENTER);
        setSpacing(true);

        VerticalLayout text = new VerticalLayout(new H2(title));
        text.setPadding(false);
        text.setSpacing(false);
        if (description != null && !description.isBlank()) {
            text.add(new Paragraph(description));
        }

        add(icon.create(), text);
    }
}
