package com.sykessec.calendarsync.scheduling;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.RuleScope;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.ics.IcsExportService;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.DiscoveredCalendar;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.provider.google.GoogleCalendarProvider;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.DeletionRuleRepository;
import com.sykessec.calendarsync.repository.RuleConditionRepository;
import com.sykessec.calendarsync.repository.RuleScopeRepository;
import com.sykessec.calendarsync.repository.SyncStateRepository;
import com.sykessec.calendarsync.rules.RuleEngine;
import com.sykessec.calendarsync.trash.TrashService;
import com.sykessec.calendarsync.util.RetryHelper;
import com.sykessec.calendarsync.util.SqliteTimestamps;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

/**
 * One firing = one connection: discover calendars on first run, then for
 * each calendar, sync events and run the rule engine against them. A
 * failure syncing one calendar is logged to that calendar's sync_state and
 * does not abort the rest of the connection's calendars - one bad calendar
 * shouldn't silently stop every other one from ever syncing again.
 *
 * Quartz instantiates this class itself (no-arg constructor), so
 * dependencies come in via field @Autowired through QuartzConfig's
 * AutowiringSpringBeanJobFactory, not constructor injection.
 */
public class ConnectionSyncJob implements Job {

    public static final String CONNECTION_ID_KEY = "connectionId";

    private static final Logger log = LoggerFactory.getLogger(ConnectionSyncJob.class);

    @Autowired
    private CalendarConnectionRepository connectionRepository;
    @Autowired
    private CalendarRepository calendarRepository;
    @Autowired
    private SyncStateRepository syncStateRepository;
    @Autowired
    private DeletionRuleRepository ruleRepository;
    @Autowired
    private RuleConditionRepository conditionRepository;
    @Autowired
    private RuleScopeRepository scopeRepository;
    @Autowired
    private RuleEngine ruleEngine;
    @Autowired
    private TrashService trashService;
    @Autowired
    private IcsExportService icsExportService;
    @Autowired
    private List<CalendarProvider> providers;

    @Override
    public void execute(JobExecutionContext context) {
        Long connectionId = context.getJobDetail().getJobDataMap().getLong(CONNECTION_ID_KEY);
        CalendarConnection connection = connectionRepository.findById(connectionId).orElse(null);
        if (connection == null) {
            log.info("Sync job fired for connection {} which no longer exists - skipping", connectionId);
            return;
        }

        CalendarProvider provider = providerFor(connection.getProvider());
        if (provider == null) {
            log.warn("No CalendarProvider registered for connection {} ({})", connectionId, connection.getProvider());
            return;
        }

        ensureCalendarsDiscovered(connection, provider);

        for (CalendarEntity calendar : calendarRepository.findAllByConnectionId(connectionId)) {
            syncCalendar(connection, provider, calendar);
        }
    }

    private void ensureCalendarsDiscovered(CalendarConnection connection, CalendarProvider provider) {
        if (!calendarRepository.findAllByConnectionId(connection.getId()).isEmpty()) {
            return;
        }
        try {
            List<DiscoveredCalendar> discovered = provider.discoverCalendars(connection);
            for (DiscoveredCalendar d : discovered) {
                CalendarEntity calendar = new CalendarEntity();
                calendar.setConnectionId(connection.getId());
                calendar.setRemoteCalendarId(d.remoteCalendarId());
                calendar.setName(d.name());
                calendar.setWritable(d.writable());
                calendarRepository.save(calendar);
            }
        } catch (ProviderException | UnsupportedOperationException e) {
            log.error("Calendar discovery failed for connection {}: {}", connection.getId(), e.getMessage());
        }
    }

    private void syncCalendar(CalendarConnection connection, CalendarProvider provider, CalendarEntity calendar) {
        SyncState syncState = syncStateRepository.findByCalendarId(calendar.getId()).orElseGet(() -> {
            SyncState s = new SyncState();
            s.setCalendarId(calendar.getId());
            return s;
        });

        try {
            List<ProviderEvent> events;
            try {
                events = RetryHelper.withRetry(3, 1000,
                        () -> provider.listEvents(connection, calendar, syncState));
            } catch (GoogleCalendarProvider.GoogleSyncTokenExpiredException expired) {
                syncState.setLastSyncToken(null);
                events = RetryHelper.withRetry(3, 1000,
                        () -> provider.listEvents(connection, calendar, syncState));
            }

            // ICS_SOURCE calendars are read-only and never targeted by a
            // calendar-scoped DELETE rule (DeletionRuleService refuses that
            // at save time) - filtering one only ever happens via a
            // published_feed, evaluated by IcsExportService instead.
            if (connection.getProvider() != ProviderType.ICS_SOURCE) {
                applyRules(connection, calendar, events);
            }

            syncState.setLastSyncedAt(SqliteTimestamps.now());
            syncState.setLastError(null);
        } catch (ProviderException e) {
            log.error("Sync failed for calendar {} ({}): {}", calendar.getId(), calendar.getName(), e.getMessage());
            syncState.setLastError(e.getMessage());
        } catch (RuntimeException e) {
            // Not every failure down here is a ProviderException: a malformed
            // CalDAV multistatus response and an unparseable rule condition
            // value both surface as unchecked exceptions. Catching only
            // ProviderException let those escape the loop in execute() and
            // skip every *other* calendar on the connection - the exact
            // opposite of this class's stated per-calendar isolation.
            log.error("Sync failed unexpectedly for calendar {} ({})", calendar.getId(), calendar.getName(), e);
            syncState.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        syncStateRepository.save(syncState);
        icsExportService.invalidateForCalendar(calendar.getId());
    }

    private void applyRules(CalendarConnection connection, CalendarEntity calendar, List<ProviderEvent> events) {
        List<Long> ruleIds = scopeRepository.findAllByCalendarId(calendar.getId()).stream()
                .map(RuleScope::getRuleId)
                .distinct()
                .toList();
        if (ruleIds.isEmpty() || events.isEmpty()) {
            return;
        }

        List<DeletionRule> rules = ruleIds.stream()
                .map(id -> ruleRepository.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(DeletionRule::isEnabled)
                .sorted(java.util.Comparator.comparingInt(DeletionRule::getPriority))
                .toList();
        if (rules.isEmpty()) {
            return;
        }

        for (ProviderEvent event : events) {
            Optional<DeletionRule> match = ruleEngine.firstMatch(event, rules,
                    ruleId -> conditionRepository.findAllByRuleId(ruleId));
            match.ifPresent(rule -> trashService.deleteWithSnapshot(
                    connection.getUserId(), rule, connection, calendar, event, rule.getAction()));
        }
    }

    private CalendarProvider providerFor(ProviderType type) {
        return providers.stream().filter(p -> p.supports(type)).findFirst().orElse(null);
    }
}
