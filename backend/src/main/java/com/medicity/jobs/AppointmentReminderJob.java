package com.medicity.jobs;

import com.medicity.scheduling.AppointmentReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every 15 minutes: queue the "your appointment is tomorrow" email for visits
 * now less than 24 hours away. Only one instance runs it ({@link JobLock}), and
 * running it again queues nothing twice; see {@link AppointmentReminderService}.
 */
@Component
@Slf4j
public class AppointmentReminderJob {

    private final JobLock lock;
    private final AppointmentReminderService reminders;

    public AppointmentReminderJob(JobLock lock, AppointmentReminderService reminders) {
        this.lock = lock;
        this.reminders = reminders;
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "UTC")
    @Transactional
    public int run() {
        if (!lock.tryAcquire("appointment-reminders")) {
            return 0;
        }
        int queued = reminders.publishDue();
        if (queued > 0) {
            log.info("Queued {} appointment reminder(s)", queued);
        }
        return queued;
    }
}
