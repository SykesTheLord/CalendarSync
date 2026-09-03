package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.CalendarConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, Long> {

    List<CalendarConnection> findAllByUserId(Long userId);

    Optional<CalendarConnection> findByIdAndUserId(Long id, Long userId);

    void deleteByIdAndUserId(Long id, Long userId);
}
