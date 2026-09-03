package com.sykessec.calendarsync.ui;

import com.sykessec.calendarsync.ui.account.AccountView;
import com.sykessec.calendarsync.ui.admin.AdminUserView;
import com.sykessec.calendarsync.ui.calendars.CalendarsView;
import com.sykessec.calendarsync.ui.connections.ConnectionsView;
import com.sykessec.calendarsync.ui.feeds.PublishedFeedsView;
import com.sykessec.calendarsync.ui.rules.RulesView;
import com.sykessec.calendarsync.ui.trash.TrashView;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;

/**
 * Must carry its own access annotation: Vaadin's AnnotatedViewAccessChecker
 * evaluates every class in a view's parent-layout chain independently, and
 * an unannotated layout is treated as MORE restrictive than a @PermitAll
 * child view - denying access to every view using this layout, including
 * @PermitAll ones, rather than falling back to "no additional restriction."
 * Individual views (e.g. AdminUserView's @RolesAllowed("ADMIN")) can still
 * narrow further; only "child broader than layout" is the trap.
 */
@PermitAll
public class MainLayout extends AppLayout {

    /** Must match the key the bootstrap script in AppShellConfig reads/writes. */
    private static final String THEME_STORAGE_KEY = "calendarsync-theme";

    private final Button themeToggle = new Button();

    public MainLayout(AuthenticationContext authenticationContext) {
        DrawerToggle toggle = new DrawerToggle();

        HorizontalLayout brand = new HorizontalLayout(VaadinIcon.CALENDAR_CLOCK.create(),
                new H1("CalendarSync"));
        brand.addClassName("cs-navbar-brand");
        brand.setAlignItems(FlexComponent.Alignment.CENTER);

        themeToggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        themeToggle.setIcon(VaadinIcon.MOON_O.create());
        themeToggle.getElement().setAttribute("aria-label", "Toggle dark mode");
        themeToggle.addClickListener(e -> toggleTheme());

        Button logout = new Button("Log out", VaadinIcon.SIGN_OUT.create(), e -> authenticationContext.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Span spacer = new Span();
        spacer.getStyle().set("flex-grow", "1");

        addToNavbar(toggle, brand, spacer, themeToggle, logout);

        SideNav nav = new SideNav();
        nav.addItem(
                new SideNavItem("Connections", ConnectionsView.class, VaadinIcon.PLUG.create()),
                new SideNavItem("Calendars", CalendarsView.class, VaadinIcon.CALENDAR.create()),
                new SideNavItem("Rules", RulesView.class, VaadinIcon.FILTER.create()),
                new SideNavItem("Published feeds", PublishedFeedsView.class, VaadinIcon.RSS.create()),
                new SideNavItem("Trash", TrashView.class, VaadinIcon.TRASH.create()),
                new SideNavItem("Account", AccountView.class, VaadinIcon.USER.create()));

        if (hasAdminAuthority()) {
            nav.addItem(new SideNavItem("Admin", AdminUserView.class, VaadinIcon.USERS.create()));
        }

        addToDrawer(nav);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // The bootstrap script in AppShellConfig already picked light/dark
        // before the first paint; read that decision back so the toggle
        // icon matches instead of assuming dark on every page load.
        readThemeState();
    }

    /**
     * Flips whatever the document is showing *right now*, read in the browser
     * at click time rather than from a Java field.
     *
     * The field this used to keep went stale: with no stored preference the
     * bootstrap script follows the OS via a matchMedia listener and rewrites
     * html[theme] without telling the server. After the OS switched to dark,
     * the field still said "light", so the first click "switched to dark" -
     * which the document already was - and nothing visibly happened. Doing the
     * read and the write in the same script removes the second copy of the
     * state that could disagree.
     */
    private void toggleTheme() {
        getElement().executeJs(
                        """
                        var root = document.documentElement;
                        var dark = root.getAttribute('theme') !== 'dark';
                        root.setAttribute('theme', dark ? 'dark' : 'light');
                        localStorage.setItem($0, dark ? 'dark' : 'light');
                        return dark;
                        """,
                        THEME_STORAGE_KEY)
                .then(Boolean.class, this::applyThemeState);
    }

    private void readThemeState() {
        getElement().executeJs("return document.documentElement.getAttribute('theme') === 'dark'")
                .then(Boolean.class, this::applyThemeState);
    }

    private void applyThemeState(boolean dark) {
        themeToggle.setIcon((dark ? VaadinIcon.SUN_O : VaadinIcon.MOON_O).create());
        themeToggle.getElement().setAttribute("aria-label",
                dark ? "Switch to light mode" : "Switch to dark mode");
    }

    private boolean hasAdminAuthority() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
