package com.sykessec.calendarsync.ui.login;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("login")
@PageTitle("Log in | CalendarSync")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassName("cs-login-page");
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        HorizontalLayout brand = new HorizontalLayout(VaadinIcon.CALENDAR_CLOCK.create(), new H1("CalendarSync"));
        brand.addClassName("cs-login-brand");
        brand.setAlignItems(Alignment.CENTER);

        LoginI18n i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle("Sign in");
        i18n.getForm().setSubmit("Log in");
        loginForm.setI18n(i18n);
        loginForm.setForgotPasswordButtonVisible(false);
        loginForm.setAction("login");

        VerticalLayout card = new VerticalLayout(brand, loginForm);
        card.addClassNames("cs-card", "cs-login-card");
        card.setPadding(false);
        card.setSpacing(false);
        // VerticalLayout's constructor sets an inline width:100%, which an
        // inline style makes unbeatable by .cs-login-card's width rule - so
        // the card stretched to its 90vw max instead of being a card. Clearing
        // the inline value hands sizing back to the stylesheet.
        card.setWidth(null);

        add(card);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }
}
