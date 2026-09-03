package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SyncStateRepository extends JpaRepository<SyncState, Long> {

    Optional<SyncState> findByCalendarId(Long calendarId);
}
