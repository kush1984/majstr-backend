package com.majstr.backend.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for the integration slice: the whole Spring context against a REAL PostgreSQL.
 *
 * <p><b>Why it exists.</b> Everything else in this suite is pure Mockito, so Flyway
 * migrations, native/JPQL queries, CHECK constraints, unique-index behaviour and the
 * security URL matrix were never executed by a test. A bad migration or a query typo shipped
 * with all tests green and surfaced only at prod startup or on the first real request.</p>
 *
 * <p><b>Postgres, never H2.</b> The migrations use PostgreSQL-specific SQL (partial indexes,
 * {@code DO $$} blocks, {@code md5()}), so an H2 shim would either fail or — worse — pass on
 * SQL that production rejects.</p>
 *
 * <p><b>One container for the whole run.</b> The field is {@code static}, so Testcontainers
 * reuses a single database across every subclass instead of paying ~5 s of startup per test
 * class. Consequence: the schema is shared, so a test must not depend on the DB being empty
 * — each one creates the rows it needs and scopes its assertions to them.</p>
 *
 * <p><b>Named {@code …IntegrationTest}, not {@code …IT}</b> — the default Gradle/JUnit
 * discovery pattern is name-based, and a test class that silently never runs is worse than
 * no test at all: it reports coverage that does not exist.</p>
 *
 * <p><b>Requires Docker.</b> Without a running daemon these tests fail at startup, they do
 * not silently skip. That is deliberate: a green build must mean the migrations really ran.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // DockerImageName, not the String overload — the latter is deprecated in Testcontainers 2.x.
    // Pinned to the same major version production runs (docker-compose.yml), so the migrations
    // are exercised against the Postgres they will actually meet.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    static {
        // Started here rather than by @Container (which would need the junit-jupiter module)
        // so the one instance outlives every class. Testcontainers' Ryuk sidecar removes it
        // when the JVM exits, so nothing is left running.
        POSTGRES.start();
    }
}
