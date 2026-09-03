package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
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
    private final CurrentUser currentUser;

    public DeletionAuditService(DeletionAuditRepository auditRepository, TrashService trashService,
                                 CurrentUser currentUser) {
        this.auditRepository = auditRepository;
        this.trashService = trashService;
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

    public RestoreOutcome restore(Long auditId) {
        return trashService.restore(currentUser.id(), auditId);
    }
}
