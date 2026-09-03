package com.sykessec.calendarsync.config;

import com.sykessec.calendarsync.ui.login.LoginView;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionRegistry sessionRegistry) throws Exception {
        // The one deliberate exception to app-wide authentication: calendar
        // clients (Proton etc.) can't do an interactive login, so this
        // endpoint is secured by its unguessable access_token instead.
        // The controller itself lands in Stage 2; permitting the path now
        // means Stage 2 doesn't have to touch security config.
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/feed/**").permitAll());

        // The OAuth redirect and callback endpoints are plain MVC controllers
        // rather than Vaadin routes, and VaadinSecurityConfigurer denies every
        // request it doesn't recognise as one of its own - so without this rule
        // picking "Google Calendar" or "Microsoft 365" in the Add connection
        // dropdown lands a logged-in user on a bare 403. Authenticated, not
        // permitAll: the connection the callback creates belongs to whoever is
        // signed in, so an anonymous caller has nothing to attach it to.
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/oauth2/**").authenticated());

        // Registers every authenticated session so UserSessionTerminator can
        // find and expire them when an account is disabled or its password is
        // reset by an admin. maximumSessions(-1) keeps sessions unlimited -
        // this is here for the registry, not to cap concurrent logins.
        http.sessionManagement(session -> session
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry));

        // Spring Security's defaults already cover nosniff, X-Frame-Options
        // and HSTS-over-TLS. These three are the ones it does NOT write by
        // default: a CSP that stops this app being framed and blocks plugin
        // and <base> injection, and a referrer policy so that a feed URL - a
        // bearer token in a path - is never handed to another origin in a
        // Referer header.
        //
        // Deliberately no script-src here: Vaadin's client bootstrap needs
        // inline script, so a script-src tight enough to be worth having needs
        // Vaadin's nonce integration and a pass through every view to verify.
        // Tracked in NOTES.md rather than shipped half-configured.
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "frame-ancestors 'none'; object-src 'none'; base-uri 'self'"))
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));

        // No view is mapped to "" (the app's routes start at /connections etc.),
        // so without this the post-login redirect - which falls back to "/" when
        // there's no saved request to return to - lands on a 404 and login looks
        // broken even though authentication itself succeeded.
        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer
                .loginView(LoginView.class)
                .defaultSuccessUrl("/connections"));

        return http.build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Without this the registry never hears about destroyed sessions and grows
     * a stale entry for every logout and every session timeout.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
