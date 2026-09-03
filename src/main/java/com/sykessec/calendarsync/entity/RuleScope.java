package com.sykessec.calendarsync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rule_scope")
public class RuleScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "calendar_id")
    private Long calendarId;

    @Column(name = "published_feed_id")
    private Long publishedFeedId;

    public Long getId() {
        return id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
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
}
