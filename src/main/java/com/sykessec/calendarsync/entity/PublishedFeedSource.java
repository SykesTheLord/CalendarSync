package com.sykessec.calendarsync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "published_feed_source")
@IdClass(PublishedFeedSourceId.class)
public class PublishedFeedSource {

    @Id
    @Column(name = "published_feed_id")
    private Long publishedFeed;

    @Id
    @Column(name = "calendar_id")
    private Long calendar;

    public PublishedFeedSource() {
    }

    public PublishedFeedSource(Long publishedFeed, Long calendar) {
        this.publishedFeed = publishedFeed;
        this.calendar = calendar;
    }

    public Long getPublishedFeed() {
        return publishedFeed;
    }

    public void setPublishedFeed(Long publishedFeed) {
        this.publishedFeed = publishedFeed;
    }

    public Long getCalendar() {
        return calendar;
    }

    public void setCalendar(Long calendar) {
        this.calendar = calendar;
    }
}
