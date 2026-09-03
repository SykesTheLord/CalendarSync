package com.sykessec.calendarsync.scheduling;

import com.sykessec.calendarsync.config.AppProperties;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Registers TrashRetentionPurgeJob on a daily cron trigger, but only if calendarsync.retention.enabled=true. */
@Component
public class RetentionJobBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionJobBootstrap.class);
    private static final JobKey JOB_KEY = new JobKey("trash-retention-purge", "calendarsync-system");

    private final Scheduler scheduler;
    private final AppProperties appProperties;

    public RetentionJobBootstrap(Scheduler scheduler, AppProperties appProperties) {
        this.scheduler = scheduler;
        this.appProperties = appProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws SchedulerException {
        if (!appProperties.getRetention().isEnabled()) {
            return;
        }

        JobDetail jobDetail = JobBuilder.newJob(TrashRetentionPurgeJob.class)
                .withIdentity(JOB_KEY)
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trash-retention-purge-trigger", "calendarsync-system")
                .forJob(jobDetail)
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(3, 30))
                .build();

        if (scheduler.checkExists(JOB_KEY)) {
            scheduler.deleteJob(JOB_KEY);
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Trash retention enabled: purging snapshots older than {} days, daily at 03:30",
                appProperties.getRetention().getDays());
    }
}
