package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.scheduling.QuartzJobScheduler;
import com.sykessec.calendarsync.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CalendarConnectionService {

    private final CalendarConnectionRepository repository;
    private final CredentialCipher credentialCipher;
    private final CurrentUser currentUser;
    private final QuartzJobScheduler jobScheduler;

    public CalendarConnectionService(CalendarConnectionRepository repository, CredentialCipher credentialCipher,
                                      CurrentUser currentUser, QuartzJobScheduler jobScheduler) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.currentUser = currentUser;
        this.jobScheduler = jobScheduler;
    }

    public List<CalendarConnection> listForCurrentUser() {
        return repository.findAllByUserId(currentUser.id());
    }

    /**
     * Credentials are encrypted via CredentialCipher whenever
     * calendarsync.db.encrypted=true (the prod profile); stored as plain
     * UTF-8 bytes otherwise (dev). OAuth flows (Google/Microsoft) and real
     * CalDAV/ICS connectivity call this same method - it only ever encodes
     * whatever string they hand it (a refresh token, a serialized MSAL
     * cache, a "username:password" pair), it doesn't care what the string means.
     */
    public CalendarConnection create(ProviderType provider, String displayName, String authType,
                                      String rawCredentials, String caldavBaseUrl) {
        if (provider == null) {
            throw new IllegalArgumentException("A connection needs a provider");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("A connection needs a display name");
        }
        requireFetchableUrl(caldavBaseUrl);

        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(currentUser.id());
        connection.setProvider(provider);
        connection.setDisplayName(displayName);
        connection.setAuthType(authType);
        connection.setCaldavBaseUrl(caldavBaseUrl);
        if (rawCredentials != null && !rawCredentials.isBlank()) {
            connection.setEncryptedCredentials(credentialCipher.encode(rawCredentials));
        }
        connection = repository.save(connection);
        jobScheduler.scheduleConnection(connection);
        return connection;
    }

    /**
     * Edits an existing connection in place. A null rawCredentials or
     * caldavBaseUrl means "leave what's stored alone" rather than "clear it":
     * the edit form hides both for providers that don't use them (an OAuth
     * connection has no URL to type, iCloud discovers its own), and the
     * password box is deliberately left blank when it's only the display name
     * or the URL being changed.
     *
     * The provider is not editable - each one stores a differently shaped
     * credential, so switching it would leave the connection holding a
     * credential its new provider can't read.
     */
    @Transactional
    public CalendarConnection update(Long connectionId, String displayName, String rawCredentials,
                                      String caldavBaseUrl) {
        CalendarConnection connection = repository.findByIdAndUserId(connectionId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your connection: " + connectionId));
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("A connection needs a display name");
        }
        requireFetchableUrl(caldavBaseUrl);

        connection.setDisplayName(displayName.trim());
        if (caldavBaseUrl != null && !caldavBaseUrl.isBlank()) {
            connection.setCaldavBaseUrl(caldavBaseUrl.trim());
        }
        if (rawCredentials != null && !rawCredentials.isBlank()) {
            connection.setEncryptedCredentials(credentialCipher.encode(rawCredentials));
        }
        return repository.save(connection);
    }

    /**
     * The username half of a stored "username:password" credential, so the
     * edit form can show which account a CalDAV or iCloud connection signs in
     * as. The password half never leaves the server. Returns null when there's
     * nothing to show - no credential, an OAuth token rather than a pair, or a
     * credential this instance can't decrypt.
     */
    public String credentialUsername(Long connectionId) {
        byte[] raw = repository.findByIdAndUserId(connectionId, currentUser.id())
                .map(CalendarConnection::getEncryptedCredentials)
                .orElse(null);
        if (raw == null) {
            return null;
        }
        try {
            String decoded = credentialCipher.decode(raw);
            int separator = decoded == null ? -1 : decoded.indexOf(':');
            return separator < 0 ? null : decoded.substring(0, separator);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * These URLs are fetched server-side by the sync job, so they decide what
     * the server connects to. Restricting the scheme blocks the obvious
     * non-HTTP abuses (file:, jar:, gopher:) that would otherwise be reachable
     * by typing them into the connection form.
     *
     * This deliberately does NOT block private or loopback addresses: a
     * self-hosted CalDAV server on the LAN is a normal, supported setup for
     * this application, so an egress policy is a deployment decision rather
     * than something to hardcode here.
     */
    private void requireFetchableUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That isn't a valid URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http:// and https:// URLs are supported, got: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("That URL has no host: " + url);
        }
    }

    /**
     * Transactional because deleteByIdAndUserId is a derived *modifying*
     * query - Spring Data refuses to run one without an active transaction.
     * The ownership check comes first so the Quartz unschedule can't be aimed
     * at another user's connection: the delete itself is user-scoped, but
     * unschedule() takes a bare id and would happily stop their sync job.
     */
    @Transactional
    public void delete(Long connectionId) {
        repository.findByIdAndUserId(connectionId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your connection: " + connectionId));
        repository.deleteByIdAndUserId(connectionId, currentUser.id());
        jobScheduler.unschedule(connectionId);
    }
}
