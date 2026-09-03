package com.sykessec.calendarsync.provider.caldav;

import com.sykessec.calendarsync.provider.ProviderException;

import java.net.URI;
import java.util.Locale;

/**
 * Every URI this package ever calls with a Basic-auth header is either the
 * base URL the user typed or something the remote server handed back - a
 * redirect Location, or an href inside a multistatus body. Those are
 * attacker-controlled if the server is hostile or compromised, and
 * CalDavClient attaches the user's CalDAV password to every request it
 * makes, so an unchecked "follow wherever it points" hands that password to
 * whatever host the response names.
 *
 * The check can't be strict same-origin: CalDAV discovery is *designed* to
 * hop hosts - iCloud answers on caldav.icloud.com and sends every real
 * request to a numbered pNN-caldav.icloud.com partition (see
 * CalDavDiscoveryService). So the rule is same-site rather than same-origin:
 * stay within the base URL's registrable domain, and never downgrade https
 * to http. A cross-site hop fails loudly instead of silently dropping the
 * credential, because a CalDAV request without auth just 401s anyway - a
 * clear error is more use than a confusing one.
 */
public final class CalDavUris {

    private CalDavUris() {
    }

    /** Resolves a server-supplied href against its base, refusing anything that leaves the base's site. */
    public static URI resolveWithinSite(URI base, String href) throws ProviderException {
        URI resolved;
        try {
            resolved = base.resolve(href);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("CalDAV server returned an unusable href: " + href, e);
        }
        requireSameSite(base, resolved);
        return resolved;
    }

    /**
     * Throws unless {@code target} is somewhere this app may send {@code base}'s
     * credentials. Called on every redirect hop and every resolved href.
     */
    public static void requireSameSite(URI base, URI target) throws ProviderException {
        String targetScheme = target.getScheme() == null ? null : target.getScheme().toLowerCase(Locale.ROOT);
        if (targetScheme == null || !(targetScheme.equals("http") || targetScheme.equals("https"))) {
            throw new ProviderException("CalDAV server pointed at a non-HTTP URL (" + target
                    + ") - refusing to send credentials there");
        }

        String baseScheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (baseScheme.equals("https") && targetScheme.equals("http")) {
            throw new ProviderException("CalDAV server tried to downgrade " + base.getHost()
                    + " from https to http (" + target + ") - refusing to send credentials in the clear");
        }

        String baseHost = host(base);
        String targetHost = host(target);
        if (baseHost == null || targetHost == null) {
            throw new ProviderException("CalDAV server pointed at a URL with no host (" + target + ")");
        }
        if (baseHost.equals(targetHost) || site(targetHost).equals(site(baseHost))) {
            return;
        }
        throw new ProviderException("CalDAV server at " + baseHost + " pointed at a different site ("
                + targetHost + ") - refusing to send credentials there. If this is a legitimate move, "
                + "update the connection's URL to the new server instead.");
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * Last two labels, as a stand-in for the registrable domain. Deliberately
     * not a Public Suffix List lookup: pulling in a PSL dependency (and
     * keeping it current) buys accuracy only for multi-label suffixes like
     * .co.uk, where this errs toward *rejecting* a legitimate hop - the safe
     * direction, and one the user can resolve by pointing the connection
     * straight at the right host.
     */
    private static String site(String host) {
        int lastDot = host.lastIndexOf('.');
        if (lastDot < 0) {
            return host;
        }
        int secondLastDot = host.lastIndexOf('.', lastDot - 1);
        return secondLastDot < 0 ? host : host.substring(secondLastDot + 1);
    }
}
