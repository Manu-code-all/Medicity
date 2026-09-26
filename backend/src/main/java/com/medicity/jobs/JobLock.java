package com.medicity.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure a scheduled job runs on one instance at a time.
 *
 * <p>Every instance of the API has the same {@code @Scheduled} methods, so with
 * two instances each job would fire twice. A PostgreSQL advisory lock settles
 * it without a lock table or an extra library: the first instance to ask for
 * the job's lock gets it, the others get {@code false} and skip this run.
 *
 * <p>Transaction-scoped ({@code pg_try_advisory_xact_lock}): the lock is
 * released when the job's transaction commits or rolls back, including when
 * the instance dies mid-job and its connection is closed. A session-level lock
 * would need an explicit unlock, and a missed one would stop the job forever
 * on that connection's pool.
 */
@Component
public class JobLock {

    private final JdbcTemplate jdbc;

    public JobLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the job's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryAcquire(String job) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, "medicity.job." + job));
    }
}
