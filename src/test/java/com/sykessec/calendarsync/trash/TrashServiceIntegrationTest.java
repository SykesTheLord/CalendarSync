package com.sykessec.calendarsync.trash;

import com.sykessec.calendarsync.AbstractIntegrationTest;
import com.sykessec.calendarsync.entity.AppUser;
import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.SyncState;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.entity.enums.SnapshotFormat;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.repository.AppUserRepository;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.DeletionAuditRepository;
import com.sykessec.calendarsync.repository.DeletionRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot-then-delete/restore round trip: delete an event, confirm a
 * restorable snapshot exists, restore it, confirm it reappears. The stub
 * provider's deleteEvent refuses to run unless a committed deletion_audit
 * row with a snapshot already exists for that event uid - this directly
 * encodes the safety requirement (never delete without a confirmed
 * snapshot) rather than just observing call order after the fact.
 */
@Transactional
class TrashServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TrashService trashService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private CalendarConnectionRepository connectionRepository;
    @Autowired
    private CalendarRepository calendarRepository;
    @Autowired
    private DeletionRuleRepository ruleRepository;
    @Autowired
    private DeletionAuditRepository auditRepository;
    @Autowired
    private RecordingCalendarProvider recordingProvider;

    private Long userId;
    private CalendarConnection connection;
    private CalendarEntity calendar;
    private DeletionRule rule;

    @BeforeEach
    void seed() {
        recordingProvider.deleteCalls.clear();
        recordingProvider.createCalls.clear();

        AppUser user = new AppUser();
        user.setUsername("alice-" + System.nanoTime());
        user.setPasswordHash("irrelevant");
        user.setRole(Role.USER);
        user.setEnabled(true);
        userId = appUserRepository.save(user).getId();

        connection = new CalendarConnection();
        connection.setUserId(userId);
        connection.setProvider(ProviderType.ICLOUD);
        connection.setDisplayName("Test iCloud");
        connection.setAuthType("app_password");
        connection = connectionRepository.save(connection);

        calendar = new CalendarEntity();
        calendar.setConnectionId(connection.getId());
        calendar.setName("Home");
        calendar.setWritable(true);
        calendar = calendarRepository.save(calendar);

        rule = new DeletionRule();
        rule.setUserId(userId);
        rule.setName("test rule");
        rule.setMatchLogic(MatchLogic.ANY);
        rule.setAction(RuleAction.DELETE);
        rule.setEnabled(true);
        rule = ruleRepository.save(rule);
    }

    private ProviderEvent eventWithSnapshot(String uid) {
        return new ProviderEvent(uid, "Team Sync", "desc", "loc", List.of(), null, null, false, false,
                "Home", SnapshotFormat.ICS, "BEGIN:VEVENT\nUID:" + uid + "\nEND:VEVENT");
    }

    @Test
    void deleteThenRestoreRoundTrip() {
        ProviderEvent event = eventWithSnapshot("evt-1");

        DeletionAudit audit = trashService.deleteWithSnapshot(userId, rule, connection, calendar, event, RuleAction.DELETE);

        assertThat(audit.getId()).isNotNull();
        assertThat(audit.getStatus()).isEqualTo(AuditStatus.DELETED);
        assertThat(audit.getEventSnapshot()).isNotBlank();
        assertThat(audit.isSuccess()).isTrue();
        assertThat(recordingProvider.deleteCalls).containsExactly("evt-1");

        RestoreOutcome outcome = trashService.restore(userId, audit.getId());
        assertThat(outcome.success()).isTrue();

        DeletionAudit reloaded = auditRepository.findById(audit.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuditStatus.RESTORED);
        assertThat(reloaded.getRestoredEventUid()).isEqualTo("restored-evt-1");
        assertThat(recordingProvider.createCalls).hasSize(1);
    }

    @Test
    void deleteIsAbortedWhenEventHasNoSnapshot() {
        ProviderEvent noSnapshot = new ProviderEvent("evt-2", "No Snapshot", null, null, List.of(),
                null, null, false, false, "Home", null, null);

        DeletionAudit audit = trashService.deleteWithSnapshot(userId, rule, connection, calendar, noSnapshot, RuleAction.DELETE);

        assertThat(audit.isSuccess()).isFalse();
        assertThat(audit.getError()).contains("Snapshot capture failed");
        assertThat(recordingProvider.deleteCalls).isEmpty();
    }

    @Test
    void dryRunNeverCallsProviderAndCannotBeRestored() {
        ProviderEvent event = eventWithSnapshot("evt-3");

        DeletionAudit audit = trashService.deleteWithSnapshot(userId, rule, connection, calendar, event, RuleAction.DRY_RUN);

        assertThat(audit.getActionTaken()).isEqualTo(RuleAction.DRY_RUN);
        assertThat(audit.isSuccess()).isTrue();
        assertThat(recordingProvider.deleteCalls).isEmpty();

        RestoreOutcome outcome = trashService.restore(userId, audit.getId());
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("dry-run");
    }

    @TestConfiguration
    static class TestProviderConfig {
        // Real provider beans (e.g. CalDavProvider) are also on the classpath
        // and also support ICLOUD - HIGHEST_PRECEDENCE ensures TrashService's
        // injected List<CalendarProvider> resolves to this stub first
        // regardless of bean registration order.
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        RecordingCalendarProvider recordingCalendarProvider() {
            return new RecordingCalendarProvider();
        }
    }

    /**
     * Throws if asked to delete an event uid that has no committed audit
     * snapshot yet - this is what actually verifies TrashService's
     * snapshot-before-delete ordering, not just a call-order spy.
     */
    static class RecordingCalendarProvider implements CalendarProvider {

        final List<String> deleteCalls = new ArrayList<>();
        final List<String> createCalls = new ArrayList<>();

        @Autowired
        private DeletionAuditRepository auditRepository;

        @Override
        public boolean supports(ProviderType type) {
            return type == ProviderType.ICLOUD;
        }

        @Override
        public List<ProviderEvent> listEvents(CalendarConnection connection, CalendarEntity calendar, SyncState syncState) {
            return List.of();
        }

        @Override
        public void deleteEvent(CalendarConnection connection, CalendarEntity calendar, ProviderEvent event)
                throws ProviderException {
            boolean snapshotCommitted = auditRepository.findAll().stream()
                    .anyMatch(a -> event.uid().equals(a.getEventUid()) && a.getEventSnapshot() != null);
            if (!snapshotCommitted) {
                throw new ProviderException("Refusing to delete " + event.uid() + " - no committed snapshot found");
            }
            deleteCalls.add(event.uid());
        }

        @Override
        public ProviderEvent createEvent(CalendarConnection connection, CalendarEntity calendar,
                                          SnapshotFormat snapshotFormat, String snapshotPayload) {
            createCalls.add(snapshotPayload);
            return new ProviderEvent("restored-evt-1", "restored", null, null, List.of(), null, null,
                    false, false, calendar.getName(), snapshotFormat, snapshotPayload);
        }
    }
}
