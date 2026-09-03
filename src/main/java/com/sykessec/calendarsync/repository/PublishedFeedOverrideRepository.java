package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.PublishedFeedOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PublishedFeedOverrideRepository extends JpaRepository<PublishedFeedOverride, Long> {

    List<PublishedFeedOverride> findAllByPublishedFeedId(Long publishedFeedId);

    Optional<PublishedFeedOverride> findByPublishedFeedIdAndEventUid(Long publishedFeedId, String eventUid);
}
