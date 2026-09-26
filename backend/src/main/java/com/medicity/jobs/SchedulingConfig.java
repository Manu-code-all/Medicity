package com.medicity.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} jobs in this package.
 *
 * <p>Off in integration tests ({@code medicity.jobs.enabled=false}), which
 * call the jobs directly: a job firing on its own schedule in the middle of a
 * test would change that test's data under it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "medicity.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
