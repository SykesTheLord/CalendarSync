package com.sykessec.calendarsync.security;

import org.springframework.security.core.AuthenticationException;

/**
 * The pending second-factor step was missing or had timed out. Its own type so
 * that the failure handler can tell "you waited too long, start again" apart
 * from "that code was wrong" - sending the first case back to a code page
 * would leave the user retyping codes against a session that can never accept
 * one.
 */
public class SecondFactorExpiredException extends AuthenticationException {

    public SecondFactorExpiredException(String message) {
        super(message);
    }
}
