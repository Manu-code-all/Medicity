package com.medicity.jobs;

import com.medicity.doctor.DoctorHoursService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly: every doctor with weekly hours has bookable slots four weeks
 * ahead. Saving hours opens the first four weeks; this adds each new day as
 * the window moves. Safe to run twice: existing slots are skipped.
 */
@Component
@Slf4j
public class DoctorHoursJob {

    private final JobLock lock;
    private final DoctorHoursService hours;

    public DoctorHoursJob(JobLock lock, DoctorHoursService hours) {
        this.lock = lock;
        this.hours = hours;
    }

    /** 03:45 UTC is 09:15 in India: after the demo reset, before the day's first visits. */
    @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
    @Transactional
    public int run() {
        if (!lock.tryAcquire("doctor-hours")) {
            return 0;
        }
        int opened = hours.rollForward();
        if (opened > 0) {
            log.info("Opened {} slot(s) from doctors' weekly hours", opened);
        }
        return opened;
    }
}
