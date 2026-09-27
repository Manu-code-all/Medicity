package com.medicity.jobs;

import com.medicity.reminder.CourseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Once a morning, in India: "your tablets run out in 3 days" and "last day
 * of your course tomorrow". 03:30 UTC is 09:00 IST, an hour after the demo
 * reset, so the demo's reminders are published on fresh data.
 *
 * <p>Running it twice publishes nothing twice; see {@link CourseService#publishReminders()}.
 */
@Component
@Slf4j
public class CourseReminderJob {

    private final JobLock lock;
    private final CourseService courses;

    public CourseReminderJob(JobLock lock, CourseService courses) {
        this.lock = lock;
        this.courses = courses;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public int run() {
        if (!lock.tryAcquire("course-reminders")) {
            return 0;
        }
        int published = courses.publishReminders();
        if (published > 0) {
            log.info("Published {} medicine reminder(s)", published);
        }
        return published;
    }
}
