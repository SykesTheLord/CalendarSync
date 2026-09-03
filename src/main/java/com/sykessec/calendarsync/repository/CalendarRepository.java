package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.CalendarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CalendarRepository extends JpaRepository<CalendarEntity, Long> {

    // "calendar" has no user_id of its own - ownership is via its parent
    // calendar_connection, so every scoped finder here joins to that table
    // rather than trusting a caller-supplied connection id.

    @Query("select c from CalendarEntity c where c.connectionId in " +
            "(select cc.id from CalendarConnection cc where cc.userId = :userId)")
    List<CalendarEntity> findAllByUserId(@Param("userId") Long userId);

    @Query("select c from CalendarEntity c where c.id = :id and c.connectionId in " +
            "(select cc.id from CalendarConnection cc where cc.userId = :userId)")
    Optional<CalendarEntity> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    List<CalendarEntity> findAllByConnectionId(Long connectionId);
}
