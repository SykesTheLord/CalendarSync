package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.entity.enums.ProviderType;
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
@Table(name = "calendar_connection")
public class CalendarConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderType provider;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "auth_type", nullable = false)
    private String authType;

    // Deliberately NOT @Lob: that maps byte[] to the JDBC BLOB type, whose
    // read path (ResultSet.getBlob()) the Xerial SQLite driver doesn't
    // implement (throws SQLFeatureNotSupportedException on every read).
    // Plain byte[] maps to VARBINARY instead, read via getBytes(), which
    // both SQLite JDBC drivers support - and SQLite's type affinity means
    // the BLOB column type in the migration still stores it identically.
    @Column(name = "encrypted_credentials")
    private byte[] encryptedCredentials;

    @Column(name = "caldav_base_url")
    private String caldavBaseUrl;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

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

    public ProviderType getProvider() {
        return provider;
    }

    public void setProvider(ProviderType provider) {
        this.provider = provider;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public byte[] getEncryptedCredentials() {
        return encryptedCredentials;
    }

    public void setEncryptedCredentials(byte[] encryptedCredentials) {
        this.encryptedCredentials = encryptedCredentials;
    }

    public String getCaldavBaseUrl() {
        return caldavBaseUrl;
    }

    public void setCaldavBaseUrl(String caldavBaseUrl) {
        this.caldavBaseUrl = caldavBaseUrl;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
