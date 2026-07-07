package no.nav.nks_ai.testutil

import org.testcontainers.containers.PostgreSQLContainer

/**
 * Singleton-PostgreSQL-container for integrasjonstester.
 * Startes én gang per JVM-prosess og deles på tvers av alle testklasser.
 */
object TestDatabase {
    val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("nks_bob_test")
            .withUsername("test")
            .withPassword("test")
            .apply { start() }
    }

    val jdbcUrl: String get() = container.jdbcUrl
}
