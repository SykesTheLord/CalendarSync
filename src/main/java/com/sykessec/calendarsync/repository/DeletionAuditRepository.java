package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * Also a JpaSpecificationExecutor: the Trash view searches and filters this
 * table, which is the one table in the schema designed to grow without bound,
 * so those have to run in the database against a page rather than by pulling
 * every row the user has ever accumulated into the browser.
 */
public interface DeletionAuditRepository extends JpaRepository<DeletionAudit, Long>,
        JpaSpecificationExecutor<DeletionAudit> {

    Optional<DeletionAudit> findByIdAndUserId(Long id, Long userId);

    /**
     * occurred_at is TEXT in "yyyy-MM-dd HH:mm:ss" (UTC) form, so a plain
     * string comparison sorts chronologically - used by the optional
     * retention purge job. System-wide, not user-scoped: retention is an
     * instance-level setting, not a per-user one.
     */
    List<DeletionAudit> findAllByStatusAndOccurredAtBefore(AuditStatus status, String occurredAt);

    /**
     * An event is either excluded from a feed or it isn't - one trash entry
     * per (feed, event), not one per regeneration. Deliberately not filtered
     * by rule or by status: a RESTORED row means the user put the event back
     * via a FORCE_INCLUDE override and must not be handed a fresh DELETED row
     * on the next regeneration, and a PURGED row means the snapshot is gone
     * on purpose and shouldn't be silently recreated.
     */
    Optional<DeletionAudit> findFirstByPublishedFeedIdAndEventUid(Long publishedFeedId, String eventUid);

    /**
     * The sync-side equivalent, used only for actions that don't actually
     * delete anything (DRY_RUN/TAG): those events keep coming back on every
     * poll, so without this the trash grows by one row per event per sync
     * cycle. Real DELETEs are not deduplicated - the event is gone from the
     * provider on success, and a failed delete SHOULD be retried next cycle.
     */
    Optional<DeletionAudit> findFirstByCalendarIdAndEventUidAndRuleIdAndStatus(
            Long calendarId, String eventUid, Long ruleId, AuditStatus status);
}
