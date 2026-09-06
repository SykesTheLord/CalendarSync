package com.sykessec.calendarsync.security;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.service.TwoFactorService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Verifies the second factor and, on success, produces the real Authentication.
 *
 * Being an AuthenticationProvider behind a ProviderManager - rather than a few
 * lines in a Vaadin click listener - is what makes the second factor a
 * first-class authentication rather than a decoration:
 *
 *   * ProviderManager publishes AuthenticationSuccessEvent and
 *     AuthenticationFailureBadCredentialsEvent, which is the ONLY thing
 *     AuthenticationEventLogger listens to and therefore the only thing that
 *     feeds LoginAttemptService. Verify a six-digit code anywhere else and it
 *     is unthrottled: 10^6 is a couple of hours of unattended guessing.
 *   * The surrounding AbstractAuthenticationProcessingFilter persists the
 *     SecurityContext and rotates the session id, so neither has to be
 *     hand-rolled here.
 *
 * The blocked-address check mirrors AppUserDetailsService and for the same
 * reason: refuse before doing any verification work, so a blocked caller
 * cannot use response timing to learn anything and cannot make the server
 * hash on its behalf.
 *
 * DELIBERATELY NOT A @Component, AND THAT IS LOAD-BEARING. Spring Boot's
 * InitializeUserDetailsManagerConfigurer stops auto-configuring a
 * DaoAuthenticationProvider from the UserDetailsService the moment ANY
 * AuthenticationProvider bean exists in the context. Publishing this one as a
 * bean therefore leaves the global AuthenticationManager holding only a
 * provider that does not support UsernamePasswordAuthenticationToken, and
 * every password login in the application fails with
 * ProviderNotFoundException - which surfaces as an ordinary "bad credentials"
 * redirect, so it looks like a wrong password rather than a broken
 * configuration. SecurityConfig constructs this by hand for the one
 * ProviderManager that should ever hold it.
 */
public class SecondFactorAuthenticationProvider implements AuthenticationProvider {

    private final AppUserRepository appUserRepository;
    private final TwoFactorService twoFactorService;
    private final LoginAttemptService loginAttemptService;

    public SecondFactorAuthenticationProvider(AppUserRepository appUserRepository,
                                              TwoFactorService twoFactorService,
                                              LoginAttemptService loginAttemptService) {
        this.appUserRepository = appUserRepository;
        this.twoFactorService = twoFactorService;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        SecondFactorAuthenticationToken request = (SecondFactorAuthenticationToken) authentication;

        String clientAddress = loginAttemptService.currentClientAddress();
        if (loginAttemptService.isBlocked(clientAddress)) {
            throw new LockedException("Too many failed sign-in attempts from this address - try again later");
        }

        AppUser user = appUserRepository.findById(request.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Unknown account"));

        // Re-checked here and not only at the password step: an admin may have
        // disabled the account in the seconds between the two.
        if (!user.isEnabled()) {
            throw new DisabledException("Account is disabled");
        }
        if (!user.isTotpEnabled()) {
            throw new BadCredentialsException("Two-factor authentication is not enabled for this account");
        }

        boolean verified = request.getSubmittedRecoveryCode() != null
                ? twoFactorService.consumeRecoveryCode(user, request.getSubmittedRecoveryCode())
                : twoFactorService.verifyCode(user, request.getSubmittedCode());

        if (!verified) {
            throw new BadCredentialsException("Incorrect verification code");
        }

        AppUserPrincipal principal = new AppUserPrincipal(user);
        return new SecondFactorCompletedAuthentication(principal, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return SecondFactorAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
