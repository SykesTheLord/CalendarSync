package com.sykessec.calendarsync.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * The authentication produced once the second factor has been accepted, as
 * distinct from the one the password step produces.
 *
 * It exists so that AuthenticationEventLogger can tell a COMPLETED login from
 * a half-finished one. Both steps publish an AuthenticationSuccessEvent, and
 * treating the first as a completed login lets a successful password reset the
 * brute-force counter on every attempt - so an attacker who already has the
 * password can guess codes indefinitely, one per login, and never trip the
 * lockout. That defeats the throttle precisely where it matters most.
 */
public class SecondFactorCompletedAuthentication extends UsernamePasswordAuthenticationToken {

    public SecondFactorCompletedAuthentication(Object principal, Collection<? extends GrantedAuthority> authorities) {
        super(principal, null, authorities);
    }
}
