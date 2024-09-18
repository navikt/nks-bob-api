package no.nav.nks_ai.plugins

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import no.nav.nks_ai.Config
import no.nav.nks_ai.DbConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

fun Application.configureDatabases() {
    Db.init(Config.db)
}

object Db {
    private lateinit var dataSource: DataSource

    fun init(config: DbConfig) {
        dataSource = HikariDataSource().apply {
            if (config.jdbcURL != null && config.jdbcURL.isNotEmpty()) {
                jdbcUrl = config.jdbcURL
            } else {
                dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
                addDataSourceProperty("serverName", config.host)
                addDataSourceProperty("portNumber", config.port)
                addDataSourceProperty("databaseName", config.database)
                addDataSourceProperty("user", config.username)
                addDataSourceProperty("password", config.password)
            }
            maximumPoolSize = 10
            minimumIdle = 1
            idleTimeout = 10001
            connectionTimeout = 1000
            maxLifetime = 30001
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
