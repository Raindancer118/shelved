package de.raindancer118.shelved;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres from Testcontainers. Without Docker, {@code SHELVED_TEST_DB_URL} points the tests at an existing,
 * throwaway database instead; it is wiped for every Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    private static final String EXTERNAL = "'${SHELVED_TEST_DB_URL:}' != ''";

    @Bean
    @ServiceConnection
    @ConditionalOnExpression("!(" + EXTERNAL + ")")
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ConditionalOnExpression(EXTERNAL)
    JdbcConnectionDetails externalDb(@Value("${SHELVED_TEST_DB_URL}") String url) {
        return new JdbcConnectionDetails() {
            @Override public String getUsername() { return "shelved"; }
            @Override public String getPassword() { return ""; }
            @Override public String getJdbcUrl() { return url; }
        };
    }

    @Bean
    @ConditionalOnExpression(EXTERNAL)
    FlywayMigrationStrategy cleanFirst() {
        return flyway -> {
            org.flywaydb.core.Flyway.configure().configuration(flyway.getConfiguration()).cleanDisabled(false).load().clean();
            flyway.migrate();
        };
    }
}
