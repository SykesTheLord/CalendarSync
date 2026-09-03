package com.sykessec.calendarsync.util;

import com.sykessec.calendarsync.provider.ProviderException;

import java.io.IOException;

/**
 * Small fixed-attempt, exponential-backoff retry for provider read calls
 * (sync polling) - scoped deliberately narrow in two ways: only listEvents
 * call sites use this, and only failures whose root cause looks like a
 * transient I/O problem (timeout, connection reset, DNS blip) are retried -
 * a ProviderException wrapping anything else (bad credentials, a 404 for a
 * deleted calendar, an unconfigured OAuth app) fails fast instead of
 * wasting the sync job's time waiting out a backoff that can't help.
 * Delete/restore calls are never retried here at all: a "failed" delete
 * might have actually succeeded server-side before a timeout, and retrying
 * a write is a different risk profile than retrying a read.
 */
public final class RetryHelper {

    private RetryHelper() {
    }

    @FunctionalInterface
    public interface ProviderCall<T> {
        T call() throws ProviderException;
    }

    public static <T> T withRetry(int maxAttempts, long initialDelayMillis, ProviderCall<T> call)
            throws ProviderException {
        ProviderException lastFailure;
        long delay = initialDelayMillis;
        int attempt = 1;

        while (true) {
            try {
                return call.call();
            } catch (ProviderException e) {
                lastFailure = e;
                if (attempt >= maxAttempts || !isTransient(e)) {
                    throw lastFailure;
                }
                // An interrupt during backoff means shutdown: give up rather
                // than burning through the remaining attempts back to back,
                // which is what happens once the interrupt flag is set and
                // every subsequent sleep throws immediately.
                if (!sleep(delay)) {
                    throw lastFailure;
                }
                delay *= 2;
                attempt++;
            }
        }
    }

    private static boolean isTransient(ProviderException e) {
        // Walk the full cause chain, not just the direct cause - e.g. Spring's
        // RestClient (used by the CalDAV client) wraps I/O failures in its
        // own ResourceAccessException before our code wraps that again.
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** Returns false if the wait was interrupted, meaning the caller should stop retrying. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
