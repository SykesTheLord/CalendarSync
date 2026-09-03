package com.sykessec.calendarsync.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limits password guessing against the login form. Spring Security does
 * no such thing on its own: without this, an instance exposed to the internet
 * will happily evaluate bcrypt for every candidate password an attacker sends,
 * forever, and the only evidence is web-server logs nobody reads.
 *
 * Keyed on client IP rather than username on purpose. A username-keyed lockout
 * lets anyone who knows a username lock that person out at will - it converts a
 * guessing attack into a denial-of-service against a real account. Blocking the
 * source instead costs an attacker their vantage point and costs a legitimate
 * user nothing unless they are behind the same address as the attacker.
 *
 * In-memory and per-instance, which matches how this application is deployed
 * (one process, one SQLite file). A restart clears the state - acceptable,
 * since an attacker cannot trigger a restart.
 */
@Component
public class LoginAttemptService {

    /** Failures from one address before it is refused. Generous enough that a person fumbling their password never hits it. */
    static final int MAX_FAILURES = 10;

    /** How long a blocked address stays blocked, and how long failures are remembered. */
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    /**
     * Bounds memory: an attacker rotating source addresses would otherwise add
     * an entry per address. Entries expire on their own, this is the backstop
     * for a burst that arrives faster than they expire.
     */
    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private final Map<String, Attempts> attemptsByAddress = new ConcurrentHashMap<>();

    public boolean isBlocked(String clientAddress) {
        if (clientAddress == null) {
            return false;
        }
        Attempts attempts = attemptsByAddress.get(clientAddress);
        if (attempts == null) {
            return false;
        }
        if (attempts.expired()) {
            attemptsByAddress.remove(clientAddress, attempts);
            return false;
        }
        return attempts.count >= MAX_FAILURES;
    }

    public void recordFailure(String clientAddress) {
        if (clientAddress == null) {
            return;
        }
        pruneIfCrowded();
        attemptsByAddress.compute(clientAddress, (key, existing) -> {
            Attempts attempts = (existing == null || existing.expired()) ? new Attempts() : existing;
            attempts.count++;
            attempts.lastFailureAt = Instant.now();
            return attempts;
        });
    }

    public void recordSuccess(String clientAddress) {
        if (clientAddress != null) {
            attemptsByAddress.remove(clientAddress);
        }
    }

    /**
     * The address the request actually came from. server.forward-headers-strategy=native
     * (application.yml) means Tomcat's RemoteIpValve has already replaced the
     * proxy's address with the X-Forwarded-For client by the time this reads it,
     * so this is the real client in both the proxied and the direct deployment.
     *
     * The "native" strategy is load-bearing for this class specifically, and must
     * not be changed back to "framework" without reading this. Both strategies
     * read X-Forwarded-For, but only native gates it on the request's source
     * address (server.tomcat.remoteip.internal-proxies); framework believes the
     * header from anyone. Since this value is the rate-limiting key, an
     * attacker who can set it freely just varies it per request and never
     * reaches MAX_FAILURES - the throttle stays green while doing nothing. That
     * was survivable only while port 8080 sat on a private network with a
     * co-located proxy as its only possible client, which is no longer the
     * deployment model.
     */
    public String currentClientAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private void pruneIfCrowded() {
        if (attemptsByAddress.size() >= MAX_TRACKED_ADDRESSES) {
            attemptsByAddress.values().removeIf(Attempts::expired);
        }
    }

    private static final class Attempts {
        private int count;
        private Instant lastFailureAt = Instant.now();

        boolean expired() {
            return Instant.now().isAfter(lastFailureAt.plus(BLOCK_DURATION));
        }
    }
}
