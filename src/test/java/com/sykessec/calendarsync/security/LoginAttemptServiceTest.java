package com.sykessec.calendarsync.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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

    /**
     * The regression this pair exists for. A blocked address is refused by
     * AppUserDetailsService with a LockedException, which is an authentication
     * failure event, which AuthenticationEventLogger feeds straight back into
     * recordFailure - so an attacker who keeps knocking used to renew their own
     * block indefinitely. Since the key is a client IP, that is a permanent
     * lockout of everyone behind a shared address, driven by someone else.
     */
    @Test
    void attemptsMadeWhileBlockedDoNotExtendTheBlock() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.recordFailure("203.0.113.7");
        }
        Instant whenBlocked = lastFailureAt(service, "203.0.113.7");

        // What a refused-while-blocked attempt looks like from here.
        for (int i = 0; i < 50; i++) {
            service.recordFailure("203.0.113.7");
        }

        assertThat(service.isBlocked("203.0.113.7")).isTrue();
        assertThat(lastFailureAt(service, "203.0.113.7"))
                .as("the block window must not slide on attempts that were already refused")
                .isEqualTo(whenBlocked);
    }

    @Test
    void theBlockLapsesOnceItsWindowHasPassed() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.recordFailure("203.0.113.7");
        }
        assertThat(service.isBlocked("203.0.113.7")).isTrue();

        // Wind the recorded failure back past the window rather than sleeping
        // fifteen minutes for it.
        backdate(service, "203.0.113.7", LoginAttemptService.BLOCK_DURATION.plusSeconds(1));

        assertThat(service.isBlocked("203.0.113.7")).isFalse();
    }

    /**
     * The map has to have a ceiling, not just an expiry sweep. Expiry alone
     * removes nothing exactly when it matters - a flood arriving faster than
     * BLOCK_DURATION - which left an unbounded map and a full scan per failure.
     */
    @Test
    void trackedAddressesAreBoundedEvenWhenNothingHasExpired() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < 10_500; i++) {
            service.recordFailure("198.51.100." + i);
        }

        assertThat(trackedAddresses(service)).isLessThanOrEqualTo(10_000);
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

    // The state these assertions need is deliberately private - a getter for
    // "when was this address last seen failing" would be API nobody else wants.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attempts(LoginAttemptService service) {
        return (Map<String, Object>) ReflectionTestUtils.getField(service, "attemptsByAddress");
    }

    private static int trackedAddresses(LoginAttemptService service) {
        return attempts(service).size();
    }

    private static Instant lastFailureAt(LoginAttemptService service, String address) {
        return (Instant) ReflectionTestUtils.getField(attempts(service).get(address), "lastFailureAt");
    }

    private static void backdate(LoginAttemptService service, String address, Duration by) {
        Object record = attempts(service).get(address);
        ReflectionTestUtils.setField(record, "lastFailureAt", lastFailureAt(service, address).minus(by));
    }
}
