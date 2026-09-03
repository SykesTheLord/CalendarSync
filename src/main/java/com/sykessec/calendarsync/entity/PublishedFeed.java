package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "published_feed")
public class PublishedFeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "access_token", nullable = false, unique = true)
    private String accessToken;

    @Column(name = "last_generated_at")
    private String lastGeneratedAt;

    @Column(name = "cache_ttl_seconds", nullable = false)
    private int cacheTtlSeconds = 3600;

    // Export settings. EnumType.STRING throughout, not ORDINAL: the columns
    // carry CHECK constraints naming the constants, and an ordinal would both
    // fail those and silently re-map every row the moment a constant is
    // inserted into the middle of an enum. Field initialisers match the
    // migration's DEFAULTs so a feed built in memory behaves like a stored one.
    @Enumerated(EnumType.STRING)
    @Column(name = "export_target", nullable = false)
    private ExportTarget exportTarget = ExportTarget.UNIVERSAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_classification", nullable = false)
    private EventClassification eventClassification = EventClassification.UNCHANGED;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_busy_status", nullable = false)
    private EventBusyStatus eventBusyStatus = EventBusyStatus.UNCHANGED;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_policy", nullable = false)
    private AlarmPolicy alarmPolicy = AlarmPolicy.STRIP;

    @Column(name = "alarm_minutes_before", nullable = false)
    private int alarmMinutesBefore = 15;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getLastGeneratedAt() {
        return lastGeneratedAt;
    }

    public void setLastGeneratedAt(String lastGeneratedAt) {
        this.lastGeneratedAt = lastGeneratedAt;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public ExportTarget getExportTarget() {
        return exportTarget;
    }

    public void setExportTarget(ExportTarget exportTarget) {
        this.exportTarget = exportTarget;
    }

    public EventClassification getEventClassification() {
        return eventClassification;
    }

    public void setEventClassification(EventClassification eventClassification) {
        this.eventClassification = eventClassification;
    }

    public EventBusyStatus getEventBusyStatus() {
        return eventBusyStatus;
    }

    public void setEventBusyStatus(EventBusyStatus eventBusyStatus) {
        this.eventBusyStatus = eventBusyStatus;
    }

    public AlarmPolicy getAlarmPolicy() {
        return alarmPolicy;
    }

    public void setAlarmPolicy(AlarmPolicy alarmPolicy) {
        this.alarmPolicy = alarmPolicy;
    }

    public int getAlarmMinutesBefore() {
        return alarmMinutesBefore;
    }

    public void setAlarmMinutesBefore(int alarmMinutesBefore) {
        this.alarmMinutesBefore = alarmMinutesBefore;
    }
}
