package com.sykessec.calendarsync;

import com.sykessec.calendarsync.config.AppProperties;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.component.page.TargetElement;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.Theme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Implements {@link AppShellConfigurator} so Vaadin has a shell class to
 * bootstrap the index page from - without one, favicon/PWA-icon detection
 * and any future {@code @Push}/{@code @Theme}/{@code @StyleSheet} annotations
 * on this class are silently ignored.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@Theme("calendarsync")
public class CalendarSyncApplication implements AppShellConfigurator {

    /**
     * Picks light/dark before Vaadin's own bootstrap paints anything, so
     * there's no flash of the wrong theme. Priority: an explicit choice the
     * user made via MainLayout's toggle (localStorage), else the OS/browser
     * preference where that can be detected, else dark - matching the
     * "default to dark, but defer to the system when possible" requirement.
     * MainLayout's toggle writes to the same "calendarsync-theme" key and
     * must stay in sync with it.
     */
    private static final String THEME_BOOTSTRAP_SCRIPT = """
            (function () {
              try {
                var STORAGE_KEY = 'calendarsync-theme';
                var stored = localStorage.getItem(STORAGE_KEY);
                var media = window.matchMedia
                    ? window.matchMedia('(prefers-color-scheme: dark)')
                    : null;
                var resolve = function () {
                  if (stored === 'dark' || stored === 'light') {
                    return stored;
                  }
                  if (media) {
                    return media.matches ? 'dark' : 'light';
                  }
                  return 'dark';
                };
                document.documentElement.setAttribute('theme', resolve());
                if (media && media.addEventListener) {
                  media.addEventListener('change', function (event) {
                    if (!localStorage.getItem(STORAGE_KEY)) {
                      document.documentElement.setAttribute('theme', event.matches ? 'dark' : 'light');
                    }
                  });
                }
              } catch (e) {
                document.documentElement.setAttribute('theme', 'dark');
              }
            })();
            """;

    public static void main(String[] args) {
        SpringApplication.run(CalendarSyncApplication.class, args);
    }

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.addInlineWithContents(TargetElement.HEAD, Inline.Position.PREPEND, THEME_BOOTSTRAP_SCRIPT,
                Inline.Wrapping.JAVASCRIPT);
    }
}
