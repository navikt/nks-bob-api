package no.nav.nks_ai.api.app.plugins

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import no.nav.nks_ai.api.app.DbConfig
import no.nav.nks_ai.api.app.getConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

fun Application.configureDatabases() {
    Db.init(getConfig().db)
    monitor.subscribe(ApplicationStopped) { Db.close() }
}

object Db {
    private lateinit var dataSource: DataSource

    fun init(config: DbConfig) {
        dataSource = HikariDataSource().apply {
            if (config.jdbcURL != null && config.jdbcURL.isNotEmpty()) {
                jdbcUrl = config.jdbcURL
                username = config.username
                password = config.password
            } else {
                dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
                addDataSourceProperty("serverName", config.host)
                addDataSourceProperty("portNumber", config.port)
                addDataSourceProperty("databaseName", config.database)
                addDataSourceProperty("user", config.username)
                addDataSourceProperty("password", config.password)
            }
            maximumPoolSize = 15
            minimumIdle = 3
            connectionTimeout = 30_000
            idleTimeout = 300_000
            maxLifetime = 1_800_000
            isAutoCommit = false
        }

        runMigration()
        Database.connect(dataSource)
    }

    fun close() {
        (dataSource as HikariDataSource).close()
    }

    private fun runMigration(initSql: String? = null): Int = Flyway
        .configure()
        .connectRetries(5)
        .dataSource(dataSource)
        .initSql(initSql)
        .validateMigrationNaming(true)
        .load()
        .migrate()
        .migrations
        .size
}
