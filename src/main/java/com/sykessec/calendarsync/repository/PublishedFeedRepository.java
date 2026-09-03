package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.PublishedFeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PublishedFeedRepository extends JpaRepository<PublishedFeed, Long> {

    List<PublishedFeed> findAllByUserId(Long userId);

    Optional<PublishedFeed> findByIdAndUserId(Long id, Long userId);

    Optional<PublishedFeed> findByAccessToken(String accessToken);
}
