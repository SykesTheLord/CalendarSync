package com.sykessec.calendarsync.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
     * Hard ceiling on tracked addresses. An attacker rotating source addresses
     * would otherwise add an entry per address, and expiry alone does not bound
     * anything when failures arrive faster than BLOCK_DURATION retires them -
     * the previous version scanned for expired entries at this size and then
     * inserted regardless, so the map grew without limit and paid an O(n) scan
     * on every failure past this point.
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

    /**
     * Records one failed attempt - and deliberately stops moving the window
     * once the address is already blocked.
     *
     * That is the whole point of the count guard below. A blocked address is
     * refused by AppUserDetailsService with a LockedException, which is itself
     * an authentication failure event, which lands back here - so every
     * refused-while-blocked attempt used to reset lastFailureAt and buy another
     * BLOCK_DURATION. An attacker hammering continuously was therefore never
     * unblocked, which sounds like a feature until you remember the key is a
     * client IP: behind a NAT, a corporate egress address, or a
     * CALENDARSYNC_TRUSTED_PROXIES list that has the proxy missing (so every
     * request looks like it came from the proxy), that is an indefinite lockout
     * of real users driven by someone else entirely. It is the denial of
     * service this class avoided by not keying on username, reintroduced
     * through the event wiring.
     *
     * So the block lasts BLOCK_DURATION from the failure that caused it, and
     * attempts made while blocked cost the attacker nothing and the victim
     * nothing either.
     */
    public void recordFailure(String clientAddress) {
        if (clientAddress == null) {
            return;
        }
        pruneIfCrowded();
        attemptsByAddress.compute(clientAddress, (key, existing) -> {
            Attempts attempts = (existing == null || existing.expired()) ? new Attempts() : existing;
            if (attempts.count < MAX_FAILURES) {
                attempts.count++;
                attempts.lastFailureAt = Instant.now();
            }
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

    /**
     * Keeps the map at or below MAX_TRACKED_ADDRESSES, by expiry first and then
     * by evicting the least recently active entries.
     *
     * The eviction step is the part that actually bounds it. Dropping only
     * expired entries is a no-op exactly when it matters - a flood arriving
     * faster than BLOCK_DURATION - and leaves an unbounded map plus a full scan
     * per failure.
     *
     * Evicting the OLDEST is the lesser of two bad options. Refusing to track
     * new addresses once full would preserve every existing block, but it also
     * hands an attacker a way to stop the throttle entirely: fill the table
     * with junk addresses, then guess passwords from an address that can never
     * be tracked. Evicting the oldest means a flood mostly evicts its own
     * earlier entries, and the worst case is that a stale block is forgotten
     * early rather than that the throttle stops working.
     */
    private void pruneIfCrowded() {
        if (attemptsByAddress.size() < MAX_TRACKED_ADDRESSES) {
            return;
        }
        attemptsByAddress.values().removeIf(Attempts::expired);

        int excess = attemptsByAddress.size() - MAX_TRACKED_ADDRESSES + 1;
        if (excess <= 0) {
            return;
        }
        attemptsByAddress.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastFailureAt))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(attemptsByAddress::remove);
    }

    /**
     * Both fields are volatile because they are written inside compute() - which
     * is atomic per key - but read outside it by isBlocked() and by the prune
     * above, and a stale read there would silently mean "not blocked".
     */
    private static final class Attempts {
        private volatile int count;
        private volatile Instant lastFailureAt = Instant.now();

        boolean expired() {
            return Instant.now().isAfter(lastFailureAt.plus(BLOCK_DURATION));
        }
    }
}
