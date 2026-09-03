package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Backs the Calendars view: a flattened list of every calendar synced across all of the user's connections. */
@Service
public class CalendarService {

    private final CalendarRepository calendarRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final CurrentUser currentUser;

    public CalendarService(CalendarRepository calendarRepository, CalendarConnectionRepository connectionRepository,
                            CurrentUser currentUser) {
        this.calendarRepository = calendarRepository;
        this.connectionRepository = connectionRepository;
        this.currentUser = currentUser;
    }

    public List<CalendarSummary> listForCurrentUser() {
        Long userId = currentUser.id();
        Map<Long, CalendarConnection> connectionsById = connectionRepository.findAllByUserId(userId).stream()
                .collect(java.util.stream.Collectors.toMap(CalendarConnection::getId, Function.identity()));

        return calendarRepository.findAllByUserId(userId).stream()
                .map(calendar -> {
                    CalendarConnection connection = connectionsById.get(calendar.getConnectionId());
                    return new CalendarSummary(
                            calendar.getId(),
                            calendar.getName(),
                            calendar.isWritable(),
                            connection == null ? null : connection.getProvider(),
                            connection == null ? "(unknown)" : connection.getDisplayName(),
                            calendar.getConnectionId());
                })
                .toList();
    }
}
