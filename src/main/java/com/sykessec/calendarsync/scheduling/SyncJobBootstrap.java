package com.sykessec.calendarsync.scheduling;

import com.sykessec.calendarsync.repository.CalendarConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Re-schedules every existing connection's sync job at startup - Quartz's RAMJobStore forgets everything on restart. */
@Component
public class SyncJobBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncJobBootstrap.class);

    private final CalendarConnectionRepository connectionRepository;
    private final QuartzJobScheduler jobScheduler;

    public SyncJobBootstrap(CalendarConnectionRepository connectionRepository, QuartzJobScheduler jobScheduler) {
        this.connectionRepository = connectionRepository;
        this.jobScheduler = jobScheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        var connections = connectionRepository.findAll();
        connections.forEach(jobScheduler::scheduleConnection);
        if (!connections.isEmpty()) {
            log.info("Scheduled sync jobs for {} existing connection(s)", connections.size());
        }
    }
}
