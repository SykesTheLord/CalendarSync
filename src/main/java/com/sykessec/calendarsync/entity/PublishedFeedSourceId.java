package com.sykessec.calendarsync.entity;

import java.io.Serializable;
import java.util.Objects;

public class PublishedFeedSourceId implements Serializable {

    private Long publishedFeed;
    private Long calendar;

    public PublishedFeedSourceId() {
    }

    public PublishedFeedSourceId(Long publishedFeed, Long calendar) {
        this.publishedFeed = publishedFeed;
        this.calendar = calendar;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PublishedFeedSourceId that)) return false;
        return Objects.equals(publishedFeed, that.publishedFeed) && Objects.equals(calendar, that.calendar);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publishedFeed, calendar);
    }
}
