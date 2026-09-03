package com.sykessec.calendarsync.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void blocksAnAddressOnlyAfterTheFailureThresholdIsReached() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.recordFailure("203.0.113.7");
            assertThat(service.isBlocked("203.0.113.7")).isFalse();
        }

        service.recordFailure("203.0.113.7");
        assertThat(service.isBlocked("203.0.113.7")).isTrue();
    }

    @Test
    void blocksOnlyTheOffendingAddress() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.recordFailure("203.0.113.7");
        }

        assertThat(service.isBlocked("203.0.113.7")).isTrue();
        assertThat(service.isBlocked("198.51.100.4")).isFalse();
    }

    @Test
    void successClearsTheFailureCount() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.recordFailure("203.0.113.7");
        }

        service.recordSuccess("203.0.113.7");

        // The pre-success failures must not carry over, or one more typo after
        // a legitimate login would lock the user out.
        service.recordFailure("203.0.113.7");
        assertThat(service.isBlocked("203.0.113.7")).isFalse();
    }

    @Test
    void toleratesAnUnknownClientAddress() {
        LoginAttemptService service = new LoginAttemptService();

        // currentClientAddress() returns null outside a servlet request (a
        // scheduled job, a test); that must never look like a blocked client.
        service.recordFailure(null);
        service.recordSuccess(null);
        assertThat(service.isBlocked(null)).isFalse();
    }
}
