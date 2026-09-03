package com.sykessec.calendarsync.provider;

/** Any failure talking to a calendar provider - auth, network, or a rejected request. */
public class ProviderException extends Exception {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
