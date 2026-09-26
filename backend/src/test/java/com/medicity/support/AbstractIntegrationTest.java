package com.medicity.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for tests that need a real database.
 *
 * <p>H2 is deliberately NOT used. The invariants this project is built around —
 * partial unique indexes, GiST exclusion constraints, {@code tstzrange} — either
 * do not exist in H2 or behave differently there. A test suite that passes on H2
 * would prove nothing about production behaviour, which is the entire point of
 * these tests.
 *
 * <p>The container is {@code static} and started once for the whole JVM, then
 * reused across every test class. Flyway migrates it on first context load.
 * Starting a container per class would add roughly a second per class for no
 * isolation benefit, since each test cleans up after itself.
 */
@SpringBootTest(properties = "medicity.jobs.enabled=false")
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            // Same major version as production (Railway runs 18). Planner,
            // locking and error-message behaviour can change between majors,
            // and a suite that proves correctness on a different version than
            // production only proves it for that version.
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("medicity_test")
                    .withUsername("medicity")
                    .withPassword("medicity")
                    // Durability is worthless for a throwaway container and
                    // fsync dominates the runtime of insert-heavy tests.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off");

    static {
        POSTGRES.start();
        System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
        System.setProperty("spring.datasource.username", POSTGRES.getUsername());
        System.setProperty("spring.datasource.password", POSTGRES.getPassword());
    }
}
