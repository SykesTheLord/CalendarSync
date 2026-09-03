package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.security.UserSessionTerminator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ADMIN-only account management. Deliberately does NOT expose any method
 * touching another user's calendars, rules, feeds, or trash - account
 * management and data access are separate concerns, and admins never get
 * implicit read access to user data in this application.
 */
@Service
public class UserAdminService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionTerminator sessionTerminator;

    public UserAdminService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                             UserSessionTerminator sessionTerminator) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionTerminator = sessionTerminator;
    }

    public List<AppUser> listAll() {
        return appUserRepository.findAll();
    }

    /** Shortest password this application will accept, anywhere it sets one. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    public AppUser createUser(String username, String rawPassword, Role role) {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A username is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("A role is required");
        }
        requireUsablePassword(rawPassword);
        if (appUserRepository.existsByUsername(trimmed)) {
            throw new IllegalArgumentException("Username already taken: " + trimmed);
        }
        AppUser user = new AppUser();
        user.setUsername(trimmed);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setEnabled(true);
        return appUserRepository.save(user);
    }

    /**
     * Refuses to disable the last enabled admin. Without this an admin can
     * lock every account out of user management with one click and no way
     * back in short of editing the database by hand.
     */
    public void setEnabled(Long userId, boolean enabled) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + userId));

        if (!enabled && user.getRole() == Role.ADMIN && lastEnabledAdmin(user)) {
            throw new IllegalArgumentException("This is the only enabled administrator - "
                    + "promote or enable another admin first, or nobody will be able to manage accounts.");
        }

        user.setEnabled(enabled);
        appUserRepository.save(user);

        // Disabling has to reach the sessions they already hold, or it only
        // takes effect the next time they voluntarily log in again.
        if (!enabled) {
            sessionTerminator.terminateSessionsFor(user.getId());
        }
    }

    /**
     * Self-service change: the current password must be presented, so a
     * walked-up-to open session can't silently take the account over.
     */
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + userId));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is not correct");
        }
        requireUsablePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("The new password must be different from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);
    }

    /**
     * Admin reset for a user who has locked themselves out. No current
     * password needed - that's the point - but it can't read the old one
     * either, and the admin has to hand the new one over out of band.
     */
    public void resetPassword(Long userId, String newPassword) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + userId));
        requireUsablePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);

        // An admin resets a password because the account is locked out or
        // believed compromised. In either case any session still running under
        // the old password should not survive the reset - unlike
        // changeOwnPassword, where the user is the one holding the session.
        sessionTerminator.terminateSessionsFor(user.getId());
    }

    private void requireUsablePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("A password is required");
        }
        if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private boolean lastEnabledAdmin(AppUser candidate) {
        return appUserRepository.findAll().stream()
                .noneMatch(u -> u.getRole() == Role.ADMIN && u.isEnabled()
                        && !u.getId().equals(candidate.getId()));
    }
}
