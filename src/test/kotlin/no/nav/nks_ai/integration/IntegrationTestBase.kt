package no.nav.nks_ai.integration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.After
import org.junit.Before
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import javax.sql.DataSource

/**
 * Base class for integration tests that require a PostgreSQL database.
 * Uses Testcontainers to spin up a real PostgreSQL database in Docker.
 *
 * Usage:
 * ```
 * class MyIntegrationTest : IntegrationTestBase() {
 *     @Test
 *     fun `test something with database`() {
 *         // Database is ready to use via Exposed
 *         // Flyway migrations have been applied
 *     }
 * }
 * ```
 */
abstract class IntegrationTestBase {

    companion object {
        /**
         * Shared PostgreSQL container for all integration tests.
         * Singleton pattern ensures container is reused across test classes.
         * Uses container reuse feature for Colima compatibility.
         *
         * Configuration is set via environment variables in build.gradle.kts:
         * - TESTCONTAINERS_RYUK_DISABLED=true
         * - TESTCONTAINERS_CHECKS_DISABLE=true
         * - TESTCONTAINERS_REUSE_ENABLE=true
         */
        private val postgresContainer: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("test_db")
                .withUsername("test_user")
                .withPassword("test_password")
                .withReuse(true)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))
                .also { it.start() }
        }

        /**
         * Data source for the test database.
         */
        private val dataSource: HikariDataSource by lazy {
            createDataSource()
        }

        /**
         * Flag to track if database has been initialized
         */
        private var isInitialized = false

        /**
         * Initialize database connection and run migrations.
         * Called once before any tests run.
         */
        fun setupDatabase() {
            if (!isInitialized) {
                runMigrations()
                Database.connect(dataSource)
                isInitialized = true
            }
        }

        private fun createDataSource(): HikariDataSource {
            val config = HikariConfig().apply {
                jdbcUrl = postgresContainer.jdbcUrl
                username = postgresContainer.username
                password = postgresContainer.password
                driverClassName = postgresContainer.driverClassName
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 30_000
                isAutoCommit = false
            }
            return HikariDataSource(config)
        }

        private fun runMigrations() {
            Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate()
        }

        /**
         * Clean up database connection.
         */
        fun teardownDatabase() {
            // Database connection is managed by the data source pool
            // Testcontainers will handle cleanup
        }
    }

    /**
     * Set up database before each test.
     * Database is clean and ready with migrations applied.
     */
    @Before
    fun setup() {
        setupDatabase()
        cleanDatabase()
    }

    /**
     * Clean up after each test.
     */
    @After
    fun teardown() {
        // Tables remain but data is cleaned between tests
    }

    /**
     * Clean all data from tables while preserving schema.
     * This is faster than dropping and recreating the database for each test.
     */
    private fun cleanDatabase() {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                // Disable foreign key checks temporarily
                statement.execute("SET session_replication_role = 'replica'")

                // Get all user tables
                val tables = mutableListOf<String>()
                val rs = statement.executeQuery(
                    """
                    SELECT tablename
                    FROM pg_tables
                    WHERE schemaname = 'public'
                    AND tablename NOT LIKE 'flyway_%'
                    """
                )
                while (rs.next()) {
                    tables.add(rs.getString("tablename"))
                }

                // Truncate all tables
                tables.forEach { table ->
                    statement.execute("TRUNCATE TABLE \"$table\" CASCADE")
                }

                // Re-enable foreign key checks
                statement.execute("SET session_replication_role = 'origin'")
            }
        }
    }

    /**
     * Get the JDBC URL for the test database.
     * Useful for debugging or custom connections.
     */
    protected fun getDatabaseUrl(): String = postgresContainer.jdbcUrl

    /**
     * Get the data source for the test database.
     * Useful for custom database operations.
     */
    protected fun getDataSource(): DataSource = dataSource
}
