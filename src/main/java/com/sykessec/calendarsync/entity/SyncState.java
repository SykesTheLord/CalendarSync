package com.sykessec.calendarsync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sync_state")
public class SyncState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "calendar_id", nullable = false)
    private Long calendarId;

    @Column(name = "last_sync_token")
    private String lastSyncToken;

    @Column(name = "last_ctag")
    private String lastCtag;

    @Column(name = "last_synced_at")
    private String lastSyncedAt;

    @Column(name = "last_error")
    private String lastError;

    public Long getId() {
        return id;
    }

    public Long getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(Long calendarId) {
        this.calendarId = calendarId;
    }

    public String getLastSyncToken() {
        return lastSyncToken;
    }

    public void setLastSyncToken(String lastSyncToken) {
        this.lastSyncToken = lastSyncToken;
    }

    public String getLastCtag() {
        return lastCtag;
    }

    public void setLastCtag(String lastCtag) {
        this.lastCtag = lastCtag;
    }

    public String getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(String lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
