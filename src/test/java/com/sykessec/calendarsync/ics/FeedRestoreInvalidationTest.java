package com.sykessec.calendarsync.ics;

import com.sykessec.calendarsync.AbstractIntegrationTest;
import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.PublishedFeedSource;
import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.RuleScope;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.DeletionAuditRepository;
import com.sykessec.calendarsync.repository.DeletionRuleRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import com.sykessec.calendarsync.repository.PublishedFeedSourceRepository;
import com.sykessec.calendarsync.repository.RuleConditionRepository;
import com.sykessec.calendarsync.repository.RuleScopeRepository;
import com.sykessec.calendarsync.security.AppUserPrincipal;
import com.sykessec.calendarsync.service.DeletionAuditService;
import com.sykessec.calendarsync.trash.RestoreOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restoring a feed-side exclusion has to drop that feed's cached bytes, or the
 * restore is only true in the database.
 *
 * This is the seam the bug lived in. TrashService.restoreFeedExclusion writes
 * the FORCE_INCLUDE override correctly and its javadoc names the caller as
 * responsible for the invalidation - because IcsExportService already depends
 * on TrashService and the reverse call would close a cycle - but
 * DeletionAuditService.restore just delegated and returned. Every existing test
 * passed: the override row was written, the audit row flipped to RESTORED, and
 * the only thing wrong was what a subscriber actually received for the next
 * cache_ttl_seconds (3600 by default). So the assertion here is deliberately on
 * the bytes the feed serves after the restore, not on any of the rows.
 */
@Transactional
class FeedRestoreInvalidationTest extends AbstractIntegrationTest {

    private static final String EVENT_UID = "boring-standup@example.com";

    @Autowired
    private IcsExportService exportService;
    @Autowired
    private DeletionAuditService auditService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private CalendarConnectionRepository connectionRepository;
    @Autowired
    private CalendarRepository calendarRepository;
    @Autowired
    private PublishedFeedRepository feedRepository;
    @Autowired
    private PublishedFeedSourceRepository sourceRepository;
    @Autowired
    private DeletionRuleRepository ruleRepository;
    @Autowired
    private RuleConditionRepository conditionRepository;
    @Autowired
    private RuleScopeRepository scopeRepository;
    @Autowired
    private DeletionAuditRepository auditRepository;

    private PublishedFeed feed;

    @BeforeEach
    void seed() {
        AppUser user = new AppUser();
        user.setUsername("feed-restore-" + System.nanoTime());
        user.setPasswordHash("irrelevant");
        user.setRole(Role.USER);
        user.setEnabled(true);
        user = appUserRepository.save(user);

        // The service reads the caller from CurrentUser, which reads the
        // security context - the restore path is user-scoped and there is no
        // point testing it around that.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AppUserPrincipal(user), null, List.of()));

        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(user.getId());
        connection.setProvider(ProviderType.ICS_SOURCE);
        connection.setDisplayName("Team feed");
        connection.setAuthType("none");
        connection = connectionRepository.save(connection);

        CalendarEntity calendar = new CalendarEntity();
        calendar.setConnectionId(connection.getId());
        calendar.setName("Team");
        calendar.setWritable(false);
        calendar = calendarRepository.save(calendar);

        feed = new PublishedFeed();
        feed.setUserId(user.getId());
        feed.setName("Filtered team feed");
        feed.setAccessToken("test-token-" + System.nanoTime());
        // The default anyway, but the point of the test is that an hour of TTL
        // is exactly how long the stale answer would survive.
        feed.setCacheTtlSeconds(3600);
        ExportProfile.DEFAULT.applyTo(feed);
        feed = feedRepository.save(feed);

        sourceRepository.save(new PublishedFeedSource(feed.getId(), calendar.getId()));

        DeletionRule rule = new DeletionRule();
        rule.setUserId(user.getId());
        rule.setName("drop standups");
        rule.setMatchLogic(MatchLogic.ANY);
        rule.setAction(RuleAction.DELETE);
        rule.setEnabled(true);
        rule = ruleRepository.save(rule);

        RuleCondition condition = new RuleCondition();
        condition.setRuleId(rule.getId());
        condition.setField(RuleField.TITLE);
        condition.setOperator(RuleOperator.CONTAINS);
        condition.setValue("Standup");
        condition.setCaseSensitive(false);
        conditionRepository.save(condition);

        RuleScope scope = new RuleScope();
        scope.setRuleId(rule.getId());
        scope.setPublishedFeedId(feed.getId());
        scopeRepository.save(scope);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        exportService.invalidate(feed.getId());
    }

    @Test
    void restoringAFeedExclusionPutsTheEventBackInTheServedFeed() throws Exception {
        // First regeneration excludes the event and fills the cache.
        assertThat(feedText()).doesNotContain(EVENT_UID);

        DeletionAudit excluded = auditRepository.findFirstByPublishedFeedIdAndEventUid(feed.getId(), EVENT_UID)
                .orElseThrow(() -> new AssertionError("the exclusion was never recorded in the trash"));
        assertThat(excluded.getStatus()).isEqualTo(AuditStatus.DELETED);

        RestoreOutcome outcome = auditService.restore(excluded.getId());
        assertThat(outcome.success()).isTrue();

        // Without the invalidation this still returns the cached bytes from the
        // first call, and the user is told the event is back while no
        // subscriber can see it for another hour.
        assertThat(feedText()).contains(EVENT_UID);
    }

    private String feedText() throws Exception {
        return new String(exportService.getOrRegenerate(feedRepository.findById(feed.getId()).orElseThrow()),
                StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class StubProviderConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        OneEventProvider oneEventProvider() {
            return new OneEventProvider();
        }
    }

    /** Serves one ICS-shaped event, so the exclusion has a snapshot to restore from. */
    static class OneEventProvider implements CalendarProvider {

        private static final String SNAPSHOT = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:%s
                DTSTAMP:20260101T000000Z
                DTSTART:20260601T090000Z
                DTEND:20260601T091500Z
                SUMMARY:Daily Standup
                END:VEVENT
                END:VCALENDAR
                """.formatted(EVENT_UID);

        @Override
        public boolean supports(ProviderType type) {
            return type == ProviderType.ICS_SOURCE;
        }

        @Override
        public List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar,
                                              SyncState syncState) {
            return List.of(new ProviderEvent(EVENT_UID, "Daily Standup", null, null, List.of(),
                    java.time.Instant.parse("2026-06-01T09:00:00Z"),
                    java.time.Instant.parse("2026-06-01T09:15:00Z"),
                    false, false, calendar.getName(), SnapshotFormat.ICS, SNAPSHOT));
        }

        /**
         * A feed-side exclusion never touches the provider - it is only ever an
         * exclusion from the output, and a restore is only ever a FORCE_INCLUDE
         * override. Both of these failing loudly is the assertion that this
         * test exercised the feed path and not the real-delete one.
         */
        @Override
        public void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event) {
            throw new AssertionError("a feed-side exclusion must not delete anything at the provider");
        }

        @Override
        public ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                                         SnapshotFormat snapshotFormat, String snapshotPayload) {
            throw new AssertionError("a feed-side restore must not call the provider");
        }
    }
}
