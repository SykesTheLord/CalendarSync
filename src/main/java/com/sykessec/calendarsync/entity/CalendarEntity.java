package com.sykessec.calendarsync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps the "calendar" table. Deliberately NOT named "Calendar" - that name
 * collides with java.util.Calendar, ical4j's net.fortuna.ical4j.model.Calendar,
 * and the Google API client's com.google.api.services.calendar.model.Calendar,
 * all of which get imported alongside this class once Stage 1 providers land.
 */
@Entity
@Table(name = "calendar")
public class CalendarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "remote_calendar_id")
    private String remoteCalendarId;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_writable", nullable = false)
    private boolean writable = true;

    private String color;

    @Column(name = "sync_token")
    private String syncToken;

    private String ctag;

    public Long getId() {
        return id;
    }

    public Long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(Long connectionId) {
        this.connectionId = connectionId;
    }

    public String getRemoteCalendarId() {
        return remoteCalendarId;
    }

    public void setRemoteCalendarId(String remoteCalendarId) {
        this.remoteCalendarId = remoteCalendarId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isWritable() {
        return writable;
    }

    public void setWritable(boolean writable) {
        this.writable = writable;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getSyncToken() {
        return syncToken;
    }

    public void setSyncToken(String syncToken) {
        this.syncToken = syncToken;
    }

    public String getCtag() {
        return ctag;
    }

    public void setCtag(String ctag) {
        this.ctag = ctag;
    }
}
