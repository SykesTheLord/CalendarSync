package com.sykessec.calendarsync.config;

import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.security.LoginAttemptService;
import com.sykessec.calendarsync.security.SecondFactorAuthenticationFilter;
import com.sykessec.calendarsync.security.SecondFactorAuthenticationProvider;
import com.sykessec.calendarsync.security.SecondFactorFailureHandler;
import com.sykessec.calendarsync.security.SecondFactorSuccessHandler;
import com.sykessec.calendarsync.security.TwoFactorAwareSuccessHandler;
import com.sykessec.calendarsync.service.TwoFactorService;
import com.sykessec.calendarsync.ui.login.LoginView;
import com.vaadin.flow.spring.security.VaadinDefaultRequestCache;
import com.vaadin.flow.spring.security.VaadinSavedRequestAwareAuthenticationSuccessHandler;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                          SessionRegistry sessionRegistry,
                                          AppUserRepository appUserRepository,
                                          TwoFactorService twoFactorService,
                                          LoginAttemptService loginAttemptService,
                                          VaadinDefaultRequestCache requestCache,
                                          ApplicationEventPublisher eventPublisher) throws Exception {
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

        // --- Two-factor authentication -----------------------------------
        //
        // The second factor is verified by a real authentication filter, not in
        // a Vaadin listener. That is what makes ProviderManager publish the
        // success/failure events AuthenticationEventLogger listens for, which
        // in turn is the ONLY thing feeding LoginAttemptService - a six-digit
        // code with no rate limit is a couple of hours of unattended guessing.
        //
        // The event publisher has to be set by hand: a ProviderManager built
        // here, rather than by Spring Security's own configuration, gets a
        // NullEventPublisher and would silently log and throttle nothing while
        // looking entirely correct.
        // Constructed here rather than injected: see the class javadoc - an
        // AuthenticationProvider @Component would disable password login for
        // the whole application.
        SecondFactorAuthenticationProvider secondFactorProvider = new SecondFactorAuthenticationProvider(
                appUserRepository, twoFactorService, loginAttemptService);
        ProviderManager secondFactorManager = new ProviderManager(secondFactorProvider);
        secondFactorManager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(eventPublisher));

        RequestMatcher verifyMatcher = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, SecondFactorAuthenticationFilter.PROCESSING_URL);

        SecondFactorAuthenticationFilter secondFactorFilter =
                new SecondFactorAuthenticationFilter(verifyMatcher, secondFactorManager);
        secondFactorFilter.setSecurityContextRepository(
                new HttpSessionSecurityContextRepository());
        secondFactorFilter.setAuthenticationSuccessHandler(new SecondFactorSuccessHandler());
        secondFactorFilter.setAuthenticationFailureHandler(new SecondFactorFailureHandler());
        http.addFilterAfter(secondFactorFilter, UsernamePasswordAuthenticationFilter.class);

        // The verify page is anonymous - the caller is by definition not
        // authenticated yet - and it is a plain form POST, so it needs its
        // CSRF token like any other. Vaadin exempts only /login and its own
        // internal requests, so nothing here is exempted: TwoFactorVerifyView
        // renders the token into the form.
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(SecondFactorAuthenticationFilter.PROCESSING_URL).permitAll());

        // Installed as a SHARED OBJECT rather than through
        // formLogin().successHandler(...), because VaadinSecurityConfigurer
        // configures form login in its own init() - which runs at http.build(),
        // i.e. after this method returns - and would overwrite anything set
        // here. It resolves its handler with
        // getSharedObject(...).orElseGet(this::createAuthenticationSuccessHandler),
        // so putting one in the shared objects is the supported way to replace it.
        //
        // Because that suppresses Vaadin's own createAuthenticationSuccessHandler(),
        // the default target URL and the request cache it would have applied
        // have to be set here instead - and VaadinSecurityConfigurer's
        // .defaultSuccessUrl() is deliberately NOT used below, because with a
        // shared object present it would be read by nobody. No view is mapped
        // to "" (routes start at /connections), so the fallback target matters:
        // without it the post-login redirect lands on a 404 and login looks
        // broken even though authentication succeeded.
        TwoFactorAwareSuccessHandler successHandler = new TwoFactorAwareSuccessHandler(appUserRepository);
        successHandler.setDefaultTargetUrl("/connections");
        successHandler.setRequestCache(requestCache);
        http.setSharedObject(VaadinSavedRequestAwareAuthenticationSuccessHandler.class, successHandler);

        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer
                .loginView(LoginView.class));

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
