package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.ics.IcsExportService;
import com.sykessec.calendarsync.repository.DeletionAuditRepository;
import com.sykessec.calendarsync.security.CurrentUser;
import com.sykessec.calendarsync.trash.RestoreOutcome;
import com.sykessec.calendarsync.trash.TrashService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

/** Backs the Trash view - the primary way a user reviews and reverses what the rule engine has done. */
@Service
public class DeletionAuditService {

    private final DeletionAuditRepository auditRepository;
    private final TrashService trashService;
    private final IcsExportService icsExportService;
    private final CurrentUser currentUser;

    public DeletionAuditService(DeletionAuditRepository auditRepository, TrashService trashService,
                                 IcsExportService icsExportService, CurrentUser currentUser) {
        this.auditRepository = auditRepository;
        this.trashService = trashService;
        this.icsExportService = icsExportService;
        this.currentUser = currentUser;
    }

    /**
     * One page of the current user's trash, with the title search and status
     * filter applied in SQL.
     *
     * The view used to load every audit row the user had and filter the list
     * in memory. This is the table that gains a row per excluded event per
     * feed, so "all of it" is the wrong amount to ask for and gets more wrong
     * the longer the application runs.
     */
    public List<DeletionAudit> search(String titleQuery, AuditStatus status, Pageable pageable) {
        return auditRepository.findAll(filter(titleQuery, status), pageable).getContent();
    }

    public long count(String titleQuery, AuditStatus status) {
        return auditRepository.count(filter(titleQuery, status));
    }

    private Specification<DeletionAudit> filter(String titleQuery, AuditStatus status) {
        Long userId = currentUser.id();
        return (root, query, cb) -> {
            var predicate = cb.equal(root.get("userId"), userId);
            if (status != null) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            if (titleQuery != null && !titleQuery.isBlank()) {
                String pattern = "%" + titleQuery.trim().toLowerCase() + "%";
                predicate = cb.and(predicate, cb.like(cb.lower(root.get("eventSummary")), pattern));
            }
            return predicate;
        };
    }

    /**
     * Restores one trash entry and, for a feed-side exclusion, drops that
     * feed's cached bytes.
     *
     * The invalidation belongs HERE rather than in TrashService, and that is
     * not tidiness. TrashService.restoreFeedExclusion writes the FORCE_INCLUDE
     * override that puts the event back, but IcsExportService already depends
     * on TrashService (it records exclusions while regenerating), so calling
     * back the other way would close a cycle. This class is the caller of
     * restore() and depends on neither in the wrong direction, which is why
     * TrashService's javadoc names it as the one responsible.
     *
     * It was named and then not implemented, which is the worst of both: a
     * restore reported success, wrote the override, and then the feed kept
     * serving the bytes it had already cached - for up to cache_ttl_seconds,
     * an hour by default. The user is told the event is back while every
     * subscriber still cannot see it.
     *
     * The feed id is read BEFORE the restore because a successful restore
     * flips the row's status, and re-reading afterwards would work but makes
     * the ordering look accidental. Ownership is enforced by the same
     * user-scoped finder restore() itself uses, so a foreign audit id resolves
     * to nothing here and invalidates nothing.
     */
    public RestoreOutcome restore(Long auditId) {
        Long userId = currentUser.id();
        Long publishedFeedId = auditRepository.findByIdAndUserId(auditId, userId)
                .map(DeletionAudit::getPublishedFeedId)
                .orElse(null);

        RestoreOutcome outcome = trashService.restore(userId, auditId);

        if (outcome.success() && publishedFeedId != null) {
            icsExportService.invalidate(publishedFeedId);
        }
        return outcome;
    }
}
