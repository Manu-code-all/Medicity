package com.medicity.jobs;

import com.medicity.audit.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring can create the demo reset bean.
 *
 * <p>The bean exists only under the {@code demo} profile, which the integration
 * tests do not use, so nothing else in the suite makes Spring construct it. It
 * once had two constructors and no {@code @Autowired}; Spring could not choose,
 * and production (which runs the demo profile) failed to start.
 */
@DisplayName("Demo reset wiring")
class DemoResetWiringTest {

    @Test
    @DisplayName("Spring can construct DemoResetJob under the demo profile")
    void beanCanBeCreated() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=demo")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(JobLock.class, () -> mock(JobLock.class))
                .withBean(AuditLog.class, () -> mock(AuditLog.class))
                .withUserConfiguration(DemoResetJob.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DemoResetJob.class);
                });
    }
}
