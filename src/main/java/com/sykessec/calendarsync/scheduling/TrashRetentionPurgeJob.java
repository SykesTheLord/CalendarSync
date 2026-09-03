package com.sykessec.calendarsync.scheduling;

import com.sykessec.calendarsync.config.AppProperties;
import com.sykessec.calendarsync.entity.DeletionAudit;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.repository.DeletionAuditRepository;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Optional (calendarsync.retention.enabled), registered by
 * RetentionJobBootstrap only when on. Retention defaults to indefinite -
 * the whole point of the trash mechanism is restorability - so this job
 * only ever runs if a user/admin has explicitly opted into a retention
 * window. A purge clears the restorable payload and marks the row PURGED
 * rather than deleting it outright, so the historical fact "this was
 * deleted on this date" survives even after the payload is gone.
 */
public class TrashRetentionPurgeJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TrashRetentionPurgeJob.class);
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired
    private DeletionAuditRepository auditRepository;
    @Autowired
    private AppProperties appProperties;

    @Override
    public void execute(JobExecutionContext context) {
        if (!appProperties.getRetention().isEnabled()) {
            return;
        }
        int days = appProperties.getRetention().getDays();
        String cutoff = FORMAT.format(Instant.now().minusSeconds(days * 86400L));

        List<DeletionAudit> stale = auditRepository.findAllByStatusAndOccurredAtBefore(AuditStatus.DELETED, cutoff);
        for (DeletionAudit audit : stale) {
            audit.setEventSnapshot(null);
            audit.setSnapshotFormat(null);
            audit.setStatus(AuditStatus.PURGED);
        }
        auditRepository.saveAll(stale);

        if (!stale.isEmpty()) {
            log.info("Purged {} deletion_audit snapshot(s) older than {} days (before {})",
                    stale.size(), days, cutoff);
        }
    }
}
