package com.sykessec.calendarsync.trash;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.PublishedFeedOverride;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.entity.enums.OverrideType;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.provider.CalendarProvider;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.provider.ProviderException;
import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.DeletionAuditRepository;
import com.sykessec.calendarsync.repository.PublishedFeedOverrideRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import com.sykessec.calendarsync.util.SqliteTimestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The trash/restore mechanism. deleteWithSnapshot is the ONLY path allowed
 * to call a provider's deleteEvent - it enforces snapshot-before-delete as a
 * hard sequencing rule (a committed deletion_audit row must exist before the
 * provider is ever asked to delete anything) rather than leaving that
 * discipline to be remembered by each provider or each caller.
 */
@Service
public class TrashService {

    private static final Logger log = LoggerFactory.getLogger(TrashService.class);

    private final DeletionAuditRepository auditRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarRepository calendarRepository;
    private final PublishedFeedRepository publishedFeedRepository;
    private final PublishedFeedOverrideRepository overrideRepository;
    private final List<CalendarProvider> providers;

    public TrashService(DeletionAuditRepository auditRepository,
                         CalendarConnectionRepository connectionRepository,
                         CalendarRepository calendarRepository,
                         PublishedFeedRepository publishedFeedRepository,
                         PublishedFeedOverrideRepository overrideRepository,
                         List<CalendarProvider> providers) {
        this.auditRepository = auditRepository;
        this.connectionRepository = connectionRepository;
        this.calendarRepository = calendarRepository;
        this.publishedFeedRepository = publishedFeedRepository;
        this.overrideRepository = overrideRepository;
        this.providers = providers;
    }

    /**
     * Records a feed-side exclusion (an ICS_SOURCE or any other provider's
     * event that a published_feed's rules would filter out of its output)
     * BEFORE the caller (IcsExportService) finalizes the excluded set -
     * same snapshot-first discipline as a real provider delete, even though
     * nothing is actually deleted anywhere here.
     */
    @Transactional
    public DeletionAudit recordFeedExclusion(Long userId, DeletionRule rule, Long publishedFeedId,
                                              CalendarConnection connection, CalendarEntity calendar,
                                              ProviderEvent event, RuleAction actionTaken) {
        // A feed regenerates on every source sync, every rule edit and every
        // cache expiry, and re-matches the same events each time. Without this
        // the trash accumulates one identical row per regeneration forever,
        // and a restored event is handed a fresh DELETED row on the very next
        // regeneration even though the FORCE_INCLUDE override is keeping it in
        // the output. Events with no uid can't be identified across runs, so
        // they fall through and are recorded unconditionally.
        if (event.uid() != null) {
            Optional<DeletionAudit> existing =
                    auditRepository.findFirstByPublishedFeedIdAndEventUid(publishedFeedId, event.uid());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        DeletionAudit audit = new DeletionAudit();
        audit.setUserId(userId);
        audit.setRuleId(rule == null ? null : rule.getId());
        audit.setConnectionId(connection == null ? null : connection.getId());
        audit.setCalendarId(calendar == null ? null : calendar.getId());
        audit.setPublishedFeedId(publishedFeedId);
        audit.setEventUid(event.uid());
        audit.setEventSummary(event.title());
        audit.setActionTaken(actionTaken);
        audit.setStatus(AuditStatus.DELETED);
        audit.setSuccess(true);
        if (event.hasSnapshot()) {
            audit.setEventSnapshot(event.rawPayload());
            audit.setSnapshotFormat(event.rawFormat());
        }
        return auditRepository.save(audit);
    }

    /**
     * Snapshot-then-delete, in that order, always. If the event carries no
     * usable snapshot, the deletion is aborted and the failure is recorded -
     * the provider's delete is never called without a durable snapshot
     * behind it. DRY_RUN rules record the same audit trail but never call
     * the provider at all.
     */
    @Transactional
    public DeletionAudit deleteWithSnapshot(Long userId, DeletionRule rule, CalendarConnection connection,
                                             CalendarEntity calendar, ProviderEvent event, RuleAction actionTaken) {
        // DRY_RUN and TAG leave the event in place, so the next sync poll sees
        // it again and would record another identical row - once per sync
        // cycle, indefinitely. A real DELETE is not deduplicated here: on
        // success the event is gone from the provider anyway, and on failure
        // retrying next cycle is the desired behaviour.
        if (actionTaken != RuleAction.DELETE && event.uid() != null) {
            Optional<DeletionAudit> existing = auditRepository
                    .findFirstByCalendarIdAndEventUidAndRuleIdAndStatus(calendar.getId(), event.uid(),
                            rule == null ? null : rule.getId(), AuditStatus.DELETED);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        DeletionAudit audit = new DeletionAudit();
        audit.setUserId(userId);
        audit.setRuleId(rule == null ? null : rule.getId());
        audit.setConnectionId(connection.getId());
        audit.setCalendarId(calendar.getId());
        audit.setEventUid(event.uid());
        audit.setEventSummary(event.title());
        audit.setActionTaken(actionTaken);
        audit.setStatus(AuditStatus.DELETED);

        if (!event.hasSnapshot()) {
            audit.setSuccess(false);
            audit.setError("Snapshot capture failed: provider returned no raw payload for this event");
            log.warn("Refusing to delete event {} on connection {} - no snapshot available",
                    event.uid(), connection.getId());
            return auditRepository.save(audit);
        }

        audit.setEventSnapshot(event.rawPayload());
        audit.setSnapshotFormat(event.rawFormat());

        // Snapshot committed to the database BEFORE any provider call.
        audit = auditRepository.save(audit);

        if (actionTaken != RuleAction.DELETE) {
            // DRY_RUN: the snapshot and audit trail exist for visibility, but
            // nothing is ever deleted, and restore() below refuses these rows.
            audit.setSuccess(true);
            return auditRepository.save(audit);
        }

        try {
            providerFor(connection.getProvider()).deleteEvent(connection, calendar, event);
            audit.setSuccess(true);
        } catch (ProviderException e) {
            audit.setSuccess(false);
            audit.setError(e.getMessage());
            log.error("Delete failed for event {} on connection {}: {}", event.uid(), connection.getId(),
                    e.getMessage());
        }

        return auditRepository.save(audit);
    }

    @Transactional
    public RestoreOutcome restore(Long userId, Long auditId) {
        DeletionAudit audit = auditRepository.findByIdAndUserId(auditId, userId)
                .orElseThrow(() -> new IllegalArgumentException("No such deletion record: " + auditId));

        if (audit.getStatus() != AuditStatus.DELETED) {
            return RestoreOutcome.failed("Only DELETED entries can be restored (this one is " + audit.getStatus() + ")");
        }
        if (audit.getActionTaken() == RuleAction.DRY_RUN) {
            return RestoreOutcome.failed("Cannot restore a dry-run record - nothing was ever deleted");
        }
        if (audit.getEventSnapshot() == null || audit.getSnapshotFormat() == null) {
            return RestoreOutcome.failed("No snapshot available to restore from (purged?)");
        }

        if (audit.getPublishedFeedId() != null) {
            return restoreFeedExclusion(userId, audit);
        }

        CalendarConnection connection = connectionRepository.findByIdAndUserId(audit.getConnectionId(), userId)
                .orElse(null);
        if (connection == null) {
            return RestoreOutcome.failed("The connection this event belonged to no longer exists");
        }
        CalendarEntity calendar = calendarRepository.findByIdAndUserId(audit.getCalendarId(), userId)
                .orElse(null);
        if (calendar == null) {
            return RestoreOutcome.failed("The destination calendar no longer exists");
        }

        try {
            ProviderEvent restored = providerFor(connection.getProvider())
                    .createEvent(connection, calendar, audit.getSnapshotFormat(), audit.getEventSnapshot());
            audit.setStatus(AuditStatus.RESTORED);
            audit.setRestoredAt(SqliteTimestamps.now());
            audit.setRestoredEventUid(restored.uid());
            auditRepository.save(audit);
            return RestoreOutcome.ok();
        } catch (ProviderException e) {
            log.error("Restore failed for deletion_audit {}: {}", auditId, e.getMessage());
            return RestoreOutcome.failed("Provider rejected the restore: " + e.getMessage());
        }
    }

    /**
     * A feed-side "deletion" is only ever an exclusion, so restoring one
     * never touches a provider - it upserts a FORCE_INCLUDE override so the
     * event reappears in that feed's output even though the rule that
     * excluded it is still enabled and would otherwise match again on the
     * next regeneration. The caller is responsible for triggering
     * regeneration (IcsExportService.invalidate) since that would otherwise
     * create a circular dependency back into this service.
     */
    private RestoreOutcome restoreFeedExclusion(Long userId, DeletionAudit audit) {
        PublishedFeed feed = publishedFeedRepository.findByIdAndUserId(audit.getPublishedFeedId(), userId)
                .orElse(null);
        if (feed == null) {
            return RestoreOutcome.failed("The published feed this event was excluded from no longer exists");
        }
        if (audit.getEventUid() == null) {
            return RestoreOutcome.failed("This record has no event uid to restore by");
        }

        PublishedFeedOverride override = overrideRepository
                .findByPublishedFeedIdAndEventUid(feed.getId(), audit.getEventUid())
                .orElseGet(() -> {
                    PublishedFeedOverride o = new PublishedFeedOverride();
                    o.setPublishedFeedId(feed.getId());
                    o.setEventUid(audit.getEventUid());
                    return o;
                });
        override.setOverride(OverrideType.FORCE_INCLUDE);
        overrideRepository.save(override);

        audit.setStatus(AuditStatus.RESTORED);
        audit.setRestoredAt(SqliteTimestamps.now());
        auditRepository.save(audit);
        return RestoreOutcome.ok();
    }

    private CalendarProvider providerFor(ProviderType type) throws ProviderException {
        return providers.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElseThrow(() -> new ProviderException("No CalendarProvider registered for " + type));
    }
}
