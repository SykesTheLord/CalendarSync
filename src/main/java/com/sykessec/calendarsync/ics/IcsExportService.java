package com.sykessec.calendarsync.ics;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.PublishedFeedOverride;
import com.sykessec.calendarsync.entity.RuleScope;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.OverrideType;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.DeletionRuleRepository;
import com.sykessec.calendarsync.repository.PublishedFeedOverrideRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import com.sykessec.calendarsync.repository.PublishedFeedSourceRepository;
import com.sykessec.calendarsync.repository.RuleConditionRepository;
import com.sykessec.calendarsync.repository.RuleScopeRepository;
import com.sykessec.calendarsync.repository.SyncStateRepository;
import com.sykessec.calendarsync.rules.RuleEngine;
import com.sykessec.calendarsync.trash.TrashService;
import com.sykessec.calendarsync.util.SqliteTimestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regenerates a published_feed's output on demand: merges its source
 * calendars' current events (across any provider mix), applies the feed's
 * rules (rule_scope.published_feed_id), applies published_feed_override,
 * and serializes the survivors via IcsCalendarMapper/CalendarOutputter under
 * the feed's ExportProfile - which decides how each surviving event is
 * written, as opposed to the rules, which decide which events survive.
 *
 * Regenerated only when triggered (a source calendar syncs, or the feed's
 * rules/scope change - see ConnectionSyncJob and DeletionRuleService) or
 * when the cache is empty/older than cache_ttl_seconds on read - never on
 * a fixed background timer of its own, per spec.
 */
@Service
public class IcsExportService {

    private static final Logger log = LoggerFactory.getLogger(IcsExportService.class);

    private final PublishedFeedRepository feedRepository;
    private final PublishedFeedSourceRepository sourceRepository;
    private final PublishedFeedOverrideRepository overrideRepository;
    private final CalendarRepository calendarRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final SyncStateRepository syncStateRepository;
    private final DeletionRuleRepository ruleRepository;
    private final RuleConditionRepository conditionRepository;
    private final RuleScopeRepository scopeRepository;
    private final RuleEngine ruleEngine;
    private final TrashService trashService;
    private final IcsCalendarMapper calendarMapper;
    private final List<CalendarProvider> providers;

    private final Map<Long, CachedFeed> cache = new ConcurrentHashMap<>();
    private final Map<Long, Object> regenerationLocks = new ConcurrentHashMap<>();

    public IcsExportService(PublishedFeedRepository feedRepository, PublishedFeedSourceRepository sourceRepository,
                             PublishedFeedOverrideRepository overrideRepository, CalendarRepository calendarRepository,
                             CalendarConnectionRepository connectionRepository, SyncStateRepository syncStateRepository,
                             DeletionRuleRepository ruleRepository, RuleConditionRepository conditionRepository,
                             RuleScopeRepository scopeRepository, RuleEngine ruleEngine, TrashService trashService,
                             IcsCalendarMapper calendarMapper, List<CalendarProvider> providers) {
        this.feedRepository = feedRepository;
        this.sourceRepository = sourceRepository;
        this.overrideRepository = overrideRepository;
        this.calendarRepository = calendarRepository;
        this.connectionRepository = connectionRepository;
        this.syncStateRepository = syncStateRepository;
        this.ruleRepository = ruleRepository;
        this.conditionRepository = conditionRepository;
        this.scopeRepository = scopeRepository;
        this.ruleEngine = ruleEngine;
        this.trashService = trashService;
        this.calendarMapper = calendarMapper;
        this.providers = providers;
    }

    /**
     * /feed/{token}.ics is unauthenticated by design, so a burst of client
     * requests arriving on a cold or expired cache would otherwise each start
     * their own regeneration - every one of them hitting every source
     * provider's API. One regeneration per feed at a time; whoever loses the
     * race re-checks the cache the winner just filled instead of repeating
     * the work.
     */
    public byte[] getOrRegenerate(PublishedFeed feed) throws ProviderException {
        CachedFeed cached = cache.get(feed.getId());
        if (cached != null && !isStale(cached, feed)) {
            return cached.bytes();
        }

        Object lock = regenerationLocks.computeIfAbsent(feed.getId(), id -> new Object());
        synchronized (lock) {
            CachedFeed afterWait = cache.get(feed.getId());
            if (afterWait != null && !isStale(afterWait, feed)) {
                return afterWait.bytes();
            }
            return regenerate(feed);
        }
    }

    private boolean isStale(CachedFeed cached, PublishedFeed feed) {
        return Instant.now().isAfter(cached.generatedAt().plusSeconds(feed.getCacheTtlSeconds()));
    }

    public byte[] regenerate(PublishedFeed feed) throws ProviderException {
        List<CalendarEntity> sourceCalendars = sourceRepository.findAllByPublishedFeed(feed.getId()).stream()
                .map(s -> calendarRepository.findById(s.getCalendar()).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        List<DeletionRule> rules = feedRules(feed.getId());

        List<PublishedFeedOverride> overrides = overrideRepository.findAllByPublishedFeedId(feed.getId());
        Map<String, OverrideType> overridesByUid = overrides.stream()
                .collect(java.util.stream.Collectors.toMap(PublishedFeedOverride::getEventUid, PublishedFeedOverride::getOverride));

        List<ProviderEvent> finalEvents = new ArrayList<>();

        for (CalendarEntity calendar : sourceCalendars) {
            CalendarConnection connection = connectionRepository.findById(calendar.getConnectionId()).orElse(null);
            if (connection == null) {
                continue;
            }
            CalendarProvider provider = providerFor(connection.getProvider());
            if (provider == null) {
                log.warn("No CalendarProvider registered for {} - skipping calendar {} in feed {}",
                        connection.getProvider(), calendar.getId(), feed.getId());
                continue;
            }

            SyncState syncState = syncStateRepository.findByCalendarId(calendar.getId()).orElseGet(SyncState::new);
            List<ProviderEvent> events;
            try {
                events = com.sykessec.calendarsync.util.RetryHelper.withRetry(3, 1000,
                        () -> provider.listEvents(connection, calendar, syncState));
            } catch (ProviderException e) {
                log.error("Failed to fetch events for calendar {} while regenerating feed {}: {}",
                        calendar.getId(), feed.getId(), e.getMessage());
                continue;
            }

            for (ProviderEvent event : events) {
                OverrideType override = event.uid() == null ? null : overridesByUid.get(event.uid());

                // A user who restored this event asked for it back explicitly.
                // Short-circuit before the rule engine so no exclusion is
                // recorded for an event that is going into the output anyway.
                if (override == OverrideType.FORCE_INCLUDE) {
                    finalEvents.add(event);
                    continue;
                }

                boolean excludedByRule = false;
                Optional<DeletionRule> match = firstMatchOrNone(event, rules, calendar.getId(), feed.getId());
                if (match.isPresent()) {
                    DeletionRule rule = match.get();
                    trashService.recordFeedExclusion(feed.getUserId(), rule, feed.getId(), connection, calendar,
                            event, rule.getAction());
                    excludedByRule = rule.getAction() == RuleAction.DELETE;
                }

                if (override != OverrideType.FORCE_EXCLUDE && !excludedByRule) {
                    finalEvents.add(event);
                }
            }
        }

        // The feed's own export profile, read fresh from the row being
        // regenerated: changing the settings invalidates the cache, so the very
        // next regeneration is what applies them.
        byte[] bytes = calendarMapper.buildFeed(finalEvents, ExportProfile.from(feed));
        cache.put(feed.getId(), new CachedFeed(bytes, Instant.now()));
        feed.setLastGeneratedAt(SqliteTimestamps.now());
        feedRepository.save(feed);
        return bytes;
    }

    public void invalidate(Long feedId) {
        cache.remove(feedId);
    }

    public void invalidateForCalendar(Long calendarId) {
        sourceRepository.findAllByCalendar(calendarId).forEach(s -> invalidate(s.getPublishedFeed()));
    }

    public void invalidateForRule(Long ruleId) {
        scopeRepository.findAllByRuleId(ruleId).stream()
                .map(RuleScope::getPublishedFeedId)
                .filter(Objects::nonNull)
                .forEach(this::invalidate);
    }

    /**
     * A condition value that can't be parsed (an unparseable START instant, a
     * non-numeric DURATION, an invalid REGEX) throws an unchecked exception
     * from deep inside the evaluator. Conditions are validated at save time
     * now, but rows predating that validation still exist, and a feed serving
     * a 500 because one rule is malformed is worse than a feed that ignores
     * that rule - so the event is kept and the problem is logged.
     */
    private Optional<DeletionRule> firstMatchOrNone(ProviderEvent event, List<DeletionRule> rules,
                                                     Long calendarId, Long feedId) {
        try {
            return ruleEngine.firstMatch(event, rules, conditionRepository::findAllByRuleId);
        } catch (RuntimeException e) {
            log.error("Rule evaluation failed for an event on calendar {} in feed {} - keeping the event. "
                    + "Check that rule's condition values: {}", calendarId, feedId, e.getMessage());
            return Optional.empty();
        }
    }

    private List<DeletionRule> feedRules(Long feedId) {
        List<Long> ruleIds = scopeRepository.findAllByPublishedFeedId(feedId).stream()
                .map(RuleScope::getRuleId)
                .distinct()
                .toList();
        return ruleIds.stream()
                .map(id -> ruleRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(DeletionRule::isEnabled)
                .sorted(Comparator.comparingInt(DeletionRule::getPriority))
                .toList();
    }

    private CalendarProvider providerFor(ProviderType type) {
        return providers.stream().filter(p -> p.supports(type)).findFirst().orElse(null);
    }

    private record CachedFeed(byte[] bytes, Instant generatedAt) {
    }
}
