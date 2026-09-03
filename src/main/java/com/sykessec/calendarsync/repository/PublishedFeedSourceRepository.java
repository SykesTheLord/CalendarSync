package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.PublishedFeedSource;
import com.sykessec.calendarsync.entity.PublishedFeedSourceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublishedFeedSourceRepository extends JpaRepository<PublishedFeedSource, PublishedFeedSourceId> {

    List<PublishedFeedSource> findAllByPublishedFeed(Long publishedFeedId);

    /** Used by the sync job to find which feeds need invalidating when a calendar syncs. */
    List<PublishedFeedSource> findAllByCalendar(Long calendarId);
}
