package com.sykessec.calendarsync.util;

import com.sykessec.calendarsync.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryHelperTest {

    @Test
    void retriesOnTransientIoFailureAndEventuallySucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String result = RetryHelper.withRetry(3, 1, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ProviderException("timeout", new IOException("connection reset"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttemptsOnPersistentTransientFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> RetryHelper.withRetry(3, 1, () -> {
            attempts.incrementAndGet();
            throw new ProviderException("still down", new IOException("connection reset"));
        })).isInstanceOf(ProviderException.class);

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> RetryHelper.withRetry(3, 1, () -> {
            attempts.incrementAndGet();
            throw new ProviderException("bad credentials");
        })).isInstanceOf(ProviderException.class);

        // No IOException anywhere in the cause chain - fails fast on the first attempt.
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void walksNestedCauseChainForTransientClassification() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException wrapped = new RuntimeException("outer", new IOException("inner timeout"));

        assertThatThrownBy(() -> RetryHelper.withRetry(2, 1, () -> {
            attempts.incrementAndGet();
            throw new ProviderException("failed", wrapped);
        })).isInstanceOf(ProviderException.class);

        assertThat(attempts.get()).isEqualTo(2);
    }
}
