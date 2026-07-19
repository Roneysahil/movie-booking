package com.roneysahil.movie_booking.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Rebuilds the test schema from scratch for each application context.
 *
 * <p>Tests commit real transactions rather than rolling back, because the concurrency
 * test needs genuine commit boundaries to prove serialization. That makes them
 * order-dependent unless the database starts from a known state, so every context clean
 * installs the migrations and seed afresh.
 */
@TestConfiguration
public class TestDatabaseConfig {

    @Bean
    FlywayMigrationStrategy cleanMigrateStrategy() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
