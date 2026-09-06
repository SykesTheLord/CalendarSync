package com.sykessec.calendarsync.ui.login;

import com.sykessec.calendarsync.security.PendingSecondFactor;
import com.sykessec.calendarsync.security.SecondFactorAuthenticationFilter;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeDetails;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.Optional;

/**
 * The second step of signing in: the page that asks for the code.
 *
 * EVERY FIELD HERE IS A NATIVE HTML INPUT, AND THAT IS THE POINT. Vaadin's
 * TextField does put its inner &lt;input&gt; in light DOM where a password manager
 * can see it, but that input carries no name attribute and the component is not
 * reliably form-associated, so a real browser form submit would post nothing.
 * Since the second factor is verified by a Spring Security filter - which is
 * what buys the rate limiting and the session handling - the submit has to be a
 * genuine POST, so the fields have to be genuine inputs.
 *
 * The autocomplete="one-time-code" attribute is what Bitwarden, 1Password and
 * iOS/Android keyboards key off to offer the stored TOTP. A two-page login is
 * the flow those tools are built around: they fill and submit username and
 * password on the first page, then offer the code here.
 *
 * The page is @AnonymousAllowed because by definition nobody is authenticated
 * yet - the pending record in the session is all that links this request to the
 * password step, and beforeEnter refuses to render anything without it.
 */
@Route("login/verify")
@PageTitle("Verification | CalendarSync")
@AnonymousAllowed
public class TwoFactorVerifyView extends VerticalLayout implements BeforeEnterObserver {

    private final VerticalLayout card = new VerticalLayout();

    public TwoFactorVerifyView() {
        addClassName("cs-login-page");
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        card.addClassNames("cs-card", "cs-login-card");
        card.setPadding(false);
        card.setSpacing(false);
        // As in LoginView: VerticalLayout's constructor sets an inline
        // width:100%, which an inline style makes unbeatable by the stylesheet.
        card.setWidth(null);
        add(card);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        PendingSecondFactor pending = pendingFromSession();
        if (pending == null || pending.isExpired()) {
            // Nothing to verify against. Sending them to the code page anyway
            // would have them retyping codes at a session that can never accept
            // one.
            event.forwardTo(LoginView.class);
            return;
        }
        card.removeAll();
        card.add(buildForm(pending, event.getLocation().getQueryParameters()
                .getParameters().containsKey("error")));
    }

    private NativeForm buildForm(PendingSecondFactor pending, boolean showError) {
        NativeForm form = new NativeForm(SecondFactorAuthenticationFilter.PROCESSING_URL);
        form.addClassName("cs-verify-form");

        HorizontalLayout brand = new HorizontalLayout(VaadinIcon.CALENDAR_CLOCK.create(), new H1("CalendarSync"));
        brand.addClassName("cs-login-brand");
        brand.setAlignItems(Alignment.CENTER);
        form.add(brand);

        form.add(new Paragraph("Signing in as " + pending.username() + "."));

        if (showError) {
            Paragraph error = new Paragraph("That code was not correct. Enter the current code and try again.");
            error.getStyle().set("color", "var(--lumo-error-text-color)");
            form.add(error);
        }

        csrfInput().ifPresent(form::add);

        NativeLabel codeLabel = new NativeLabel("Authentication code");
        codeLabel.setFor("cs-totp-code");
        form.add(codeLabel, codeInput());

        // A details element rather than a second always-visible field: an empty
        // recovery-code box next to the code box invites people to fill in the
        // wrong one, and password managers occasionally autofill into it.
        form.add(recoveryDetails());

        Button submit = new Button("Verify");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submit.getElement().setAttribute("type", "submit");
        form.add(submit);

        Anchor cancel = new Anchor("/login", "Cancel and sign in again");
        cancel.getStyle().set("font-size", "var(--lumo-font-size-s)");
        form.add(cancel);

        return form;
    }

    private Input codeInput() {
        Input code = new Input();
        code.setId("cs-totp-code");
        code.getElement().setAttribute("name", "code");
        // The attribute password managers and mobile keyboards look for.
        code.getElement().setAttribute("autocomplete", "one-time-code");
        code.getElement().setAttribute("inputmode", "numeric");
        code.getElement().setAttribute("pattern", "[0-9]*");
        code.getElement().setAttribute("maxlength", "6");
        code.getElement().setAttribute("autofocus", true);
        code.setPlaceholder("123456");
        return code;
    }

    private NativeDetails recoveryDetails() {
        Input recovery = new Input();
        recovery.getElement().setAttribute("name", "recoveryCode");
        recovery.getElement().setAttribute("autocomplete", "off");
        recovery.setPlaceholder("XXXXX-XXXXX");

        Paragraph help = new Paragraph("Each recovery code works once.");
        help.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");

        VerticalLayout content = new VerticalLayout(help, recovery);
        content.setPadding(false);
        content.setSpacing(false);

        return new NativeDetails("Use a recovery code instead", content);
    }

    /**
     * The CSRF token, as a hidden field.
     *
     * Vaadin's security configurer exempts exactly two things from CSRF: its own
     * internal requests, and the form-login page path. /login/verify is neither,
     * so this POST is checked like any other and needs the token. Returned as an
     * Optional because a missing token must not throw here - the request would
     * simply be rejected by the CsrfFilter, which is the safe direction.
     */
    private Optional<Input> csrfInput() {
        HttpServletRequest request = VaadinServletRequest.getCurrent();
        if (request == null) {
            return Optional.empty();
        }
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (!(attribute instanceof CsrfToken token)) {
            return Optional.empty();
        }
        Input hidden = new Input();
        hidden.setType("hidden");
        hidden.getElement().setAttribute("name", token.getParameterName());
        hidden.setValue(token.getToken());
        return Optional.of(hidden);
    }

    private PendingSecondFactor pendingFromSession() {
        HttpServletRequest request = VaadinServletRequest.getCurrent();
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object pending = session.getAttribute(PendingSecondFactor.SESSION_ATTRIBUTE);
        return pending instanceof PendingSecondFactor record ? record : null;
    }
}
