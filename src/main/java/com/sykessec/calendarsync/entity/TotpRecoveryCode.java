package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.util.SqliteTimestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One single-use recovery code, stored as a SHA-256 hash of the code the user
 * was shown once at enrolment.
 *
 * Consumed by setting usedAt rather than by deleting the row, so that "how many
 * do I have left" is answerable and so that a support conversation can tell
 * "this account never had codes" apart from "this account has burned all ten".
 *
 * On the hash choice: deliberately not the bcrypt PasswordEncoder that
 * password_hash uses. Checking a submitted code means testing it against every
 * unused row for that user, on an endpoint reachable before authentication has
 * completed - ten bcrypt comparisons per guess is about a second of CPU an
 * unauthenticated caller can spend at will. bcrypt's cost exists to protect
 * secrets a human chose badly; these carry 50 bits from a SecureRandom, so
 * there is nothing for an attacker to shortcut and nothing for a slow hash to
 * buy.
 */
@Entity
@Table(name = "totp_recovery_code")
public class TotpRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "used_at")
    private String usedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = SqliteTimestamps.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(String usedAt) {
        this.usedAt = usedAt;
    }
}
