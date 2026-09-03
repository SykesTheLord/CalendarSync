package com.sykessec.calendarsync.provider.caldav;

/** Basic auth credentials - for iCloud, password is an app-specific password, never the Apple ID password. */
public record CalDavCredentials(String username, String password) {
}
