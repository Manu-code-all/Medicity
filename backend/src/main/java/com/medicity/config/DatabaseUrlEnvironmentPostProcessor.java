package com.medicity.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a platform-supplied {@code DATABASE_URL} into the JDBC properties
 * Spring expects.
 *
 * <h2>Why this is needed</h2>
 *
 * Railway, Render, Heroku and Fly all inject the database connection as a
 * libpq-style URL:
 *
 * <pre>postgresql://user:password@host:5432/dbname</pre>
 *
 * Spring's {@code spring.datasource.url} needs a JDBC URL, with the credentials
 * supplied separately:
 *
 * <pre>jdbc:postgresql://host:5432/dbname</pre>
 *
 * Handing the first form to Spring produces
 * {@code Driver claims to not accept jdbcUrl}, which is an unhelpful way to
 * discover a URL-format mismatch at 2am. The usual workaround is to hand-wire
 * three variables per environment; doing the conversion here means the app runs
 * unmodified on any of those platforms.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a {@code @Configuration}
 * class because the DataSource is built during context refresh — a bean would be
 * created too late to influence it.
 *
 * <p>An explicit {@code DB_URL} always wins, so this never overrides a
 * deliberate local or CI setting.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "medicityDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty("DATABASE_URL");

        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        if (environment.getProperty("DB_URL") != null) {
            // The operator was explicit; respect that over the platform default.
            return;
        }
        if (databaseUrl.startsWith("jdbc:")) {
            // Already a JDBC URL — some platforms do supply one. Pass it through
            // untouched rather than mangling it.
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME, Map.of("spring.datasource.url", databaseUrl)));
            return;
        }

        try {
            URI uri = new URI(databaseUrl);
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();

            Map<String, Object> properties = new HashMap<>();
            properties.put("spring.datasource.url",
                    "jdbc:postgresql://%s:%d%s".formatted(uri.getHost(), port, uri.getPath()));

            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                // Split on the FIRST colon only: passwords routinely contain
                // colons, and splitting on all of them silently truncates them.
                int separator = userInfo.indexOf(':');
                if (separator >= 0) {
                    properties.put("spring.datasource.username", userInfo.substring(0, separator));
                    properties.put("spring.datasource.password", userInfo.substring(separator + 1));
                } else {
                    properties.put("spring.datasource.username", userInfo);
                }
            }

            environment.getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));

        } catch (URISyntaxException e) {
            // Failing loudly here beats letting Hikari fail later with a message
            // that says nothing about where the bad value came from.
            throw new IllegalStateException(
                    "DATABASE_URL is not a valid URI. Expected postgresql://user:password@host:port/db", e);
        }
    }

    @Override
    public int getOrder() {
        // After config files are loaded, so an explicit DB_URL in a profile is visible.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
