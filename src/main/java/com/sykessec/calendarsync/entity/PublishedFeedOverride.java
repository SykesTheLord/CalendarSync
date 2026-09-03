package com.sykessec.calendarsync.entity;

import com.sykessec.calendarsync.entity.enums.OverrideType;
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
@Table(name = "published_feed_override")
public class PublishedFeedOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "published_feed_id", nullable = false)
    private Long publishedFeedId;

    @Column(name = "event_uid", nullable = false)
    private String eventUid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverrideType override;

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

    public OverrideType getOverride() {
        return override;
    }

    public void setOverride(OverrideType override) {
        this.override = override;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
