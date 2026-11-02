package com.majstr.backend.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway connects on its own, outside the pool. The pool's connections carry a statement_timeout
 * (application.yml), right for requests and wrong for a migration, where a statement past it would stop
 * the deploy.
 */
@Configuration
public class FlywayConnectionConfig {

    @Bean
    FlywayConfigurationCustomizer flywayOutsideThePool(JdbcConnectionDetails database) {
        return flyway -> flyway.dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }
}
