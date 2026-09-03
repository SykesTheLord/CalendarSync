package com.sykessec.calendarsync.scheduling;

import com.sykessec.calendarsync.entity.CalendarConnection;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Service;

/**
 * Creates/reschedules/removes the one Quartz job+trigger per connection.
 * Quartz's default RAMJobStore (no jdbc job store configured - there's no
 * Quartz table in the app's own schema) doesn't persist jobs across
 * restarts, so SyncJobBootstrap re-schedules every existing connection at
 * application startup; this class is just the mechanics either call site
 * uses.
 */
@Service
public class QuartzJobScheduler {

    private static final String GROUP = "calendarsync-connections";

    private final Scheduler scheduler;

    public QuartzJobScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void scheduleConnection(CalendarConnection connection) {
        JobKey jobKey = jobKeyFor(connection.getId());
        JobDetail jobDetail = JobBuilder.newJob(ConnectionSyncJob.class)
                .withIdentity(jobKey)
                .usingJobData(ConnectionSyncJob.CONNECTION_ID_KEY, connection.getId())
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKeyFor(connection.getId()))
                .forJob(jobDetail)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(defaultIntervalMinutesFor(connection.getProvider()))
                        .repeatForever())
                .build();

        try {
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
            scheduler.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to schedule sync job for connection " + connection.getId(), e);
        }
    }

    public void unschedule(Long connectionId) {
        try {
            scheduler.deleteJob(jobKeyFor(connectionId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to unschedule sync job for connection " + connectionId, e);
        }
    }

    /** Suggested default cadences per spec; not yet exposed as a per-connection user setting. */
    private int defaultIntervalMinutesFor(ProviderType type) {
        return switch (type) {
            case GOOGLE, MS_GRAPH -> 10;
            case ICLOUD, CALDAV -> 12;
            case ICS_SOURCE -> 60;
        };
    }

    private JobKey jobKeyFor(Long connectionId) {
        return new JobKey("connection-sync-" + connectionId, GROUP);
    }

    private TriggerKey triggerKeyFor(Long connectionId) {
        return new TriggerKey("connection-sync-trigger-" + connectionId, GROUP);
    }
}
