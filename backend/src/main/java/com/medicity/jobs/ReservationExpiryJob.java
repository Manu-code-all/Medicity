package com.medicity.jobs;

import com.medicity.request.MedicineRequestService;
import com.medicity.request.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every minute: reservations nobody collected go back on the shelf, and
 * questions past their six hours close.
 *
 * <p>A minute is the promise to the store: "keep it aside for 3 hours" means
 * the store is told within a minute of the third hour that it can sell the
 * stock again. Each run is one indexed UPDATE on the few held rows.
 *
 * <p>Correct without the job, only later: collecting checks the expiry in its
 * own UPDATE, and asking again expires the old question first. The job makes
 * expiry visible (notifications, the queue) rather than making it true.
 */
@Component
@Slf4j
public class ReservationExpiryJob {

    private final JobLock lock;
    private final ReservationService reservations;
    private final MedicineRequestService requests;

    public ReservationExpiryJob(JobLock lock, ReservationService reservations, MedicineRequestService requests) {
        this.lock = lock;
        this.reservations = reservations;
        this.requests = requests;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    @Transactional
    public Result run() {
        if (!lock.tryAcquire("reservation-expiry")) {
            return new Result(0, 0);
        }
        // Reservations first: an expired reservation reopens its question, which
        // the second step then expires if the question's own time is up too.
        Result result = new Result(reservations.expireOverdue(), requests.expireOverdue());
        if (result.reservations() + result.questions() > 0) {
            log.info("Expired {} reservation(s) and {} question(s)", result.reservations(), result.questions());
        }
        return result;
    }

    public record Result(int reservations, int questions) {}
}
