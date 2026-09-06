package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.util.SqliteTimestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    /**
     * The TOTP shared secret, encrypted by TotpSecretCipher. Present but
     * unconfirmed during enrolment is impossible by construction: nothing
     * writes this column until a code generated from it has been verified,
     * so a half-finished enrolment cannot lock anybody out.
     */
    @Column(name = "totp_secret")
    private byte[] totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    /** Admin-set. Independent of totpEnabled - see TwoFactorService. */
    @Column(name = "totp_required", nullable = false)
    private boolean totpRequired;

    @Column(name = "totp_confirmed_at")
    private String totpConfirmedAt;

    /**
     * The last time step accepted for this user. A code is valid across three
     * steps, so without this a code seen once can be replayed for up to 90
     * seconds; verification requires a strictly greater step than this one.
     */
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = SqliteTimestamps.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public byte[] getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(byte[] totpSecret) {
        this.totpSecret = totpSecret;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public void setTotpEnabled(boolean totpEnabled) {
        this.totpEnabled = totpEnabled;
    }

    public boolean isTotpRequired() {
        return totpRequired;
    }

    public void setTotpRequired(boolean totpRequired) {
        this.totpRequired = totpRequired;
    }

    public String getTotpConfirmedAt() {
        return totpConfirmedAt;
    }

    public void setTotpConfirmedAt(String totpConfirmedAt) {
        this.totpConfirmedAt = totpConfirmedAt;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }
}
