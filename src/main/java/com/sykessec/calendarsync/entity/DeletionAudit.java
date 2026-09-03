package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.util.SqliteTimestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One row per deletion (real or feed-side). event_snapshot must contain
 * enough to fully recreate the event - this table IS the trash/restore
 * mechanism, not just a log.
 */
@Entity
@Table(name = "deletion_audit")
public class DeletionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "calendar_id")
    private Long calendarId;

    @Column(name = "published_feed_id")
    private Long publishedFeedId;

    @Column(name = "event_uid")
    private String eventUid;

    @Column(name = "event_summary")
    private String eventSummary;

    @Lob
    @Column(name = "event_snapshot")
    private String eventSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_format")
    private SnapshotFormat snapshotFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_taken", nullable = false)
    private RuleAction actionTaken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditStatus status = AuditStatus.DELETED;

    @Column(name = "occurred_at", nullable = false)
    private String occurredAt;

    @Column(name = "restored_at")
    private String restoredAt;

    @Column(name = "restored_event_uid")
    private String restoredEventUid;

    @Column(nullable = false)
    private boolean success;

    private String error;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = SqliteTimestamps.now();
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

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(Long connectionId) {
        this.connectionId = connectionId;
    }

    public Long getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(Long calendarId) {
        this.calendarId = calendarId;
    }

    public Long getPublishedFeedId() {
        return publishedFeedId;
    }

    public void setPublishedFeedId(Long publishedFeedId) {
        this.publishedFeedId = publishedFeedId;
    }

    public String getEventUid() {
        return eventUid;
    }

    public void setEventUid(String eventUid) {
        this.eventUid = eventUid;
    }

    public String getEventSummary() {
        return eventSummary;
    }

    public void setEventSummary(String eventSummary) {
        this.eventSummary = eventSummary;
    }

    public String getEventSnapshot() {
        return eventSnapshot;
    }

    public void setEventSnapshot(String eventSnapshot) {
        this.eventSnapshot = eventSnapshot;
    }

    public SnapshotFormat getSnapshotFormat() {
        return snapshotFormat;
    }

    public void setSnapshotFormat(SnapshotFormat snapshotFormat) {
        this.snapshotFormat = snapshotFormat;
    }

    public RuleAction getActionTaken() {
        return actionTaken;
    }

    public void setActionTaken(RuleAction actionTaken) {
        this.actionTaken = actionTaken;
    }

    public AuditStatus getStatus() {
        return status;
    }

    public void setStatus(AuditStatus status) {
        this.status = status;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public String getRestoredAt() {
        return restoredAt;
    }

    public void setRestoredAt(String restoredAt) {
        this.restoredAt = restoredAt;
    }

    public String getRestoredEventUid() {
        return restoredEventUid;
    }

    public void setRestoredEventUid(String restoredEventUid) {
        this.restoredEventUid = restoredEventUid;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
