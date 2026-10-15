package no.nav.nks_ai.delete_active_connections

import arrow.core.getOrElse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.getAs
import kotlinx.serialization.json.Json
import no.nav.nks_ai.shared.DeleteExpiredActiveConnectionsSummary
import no.nav.nks_ai.shared.auth.TexasClient
import java.util.UUID

private val logger = KotlinLogging.logger {}

internal const val CLEANUP_PATH = "/api/v1/admin/jobs/delete-expired-active-connections"

suspend fun main() {
    val config = ApplicationConfig("application.conf").getAs<Config>()
    createHttpClient().use { client ->
        val summary = deleteExpiredConnections(config, client)
        logger.info { "Deleted ${summary.deletedConnections} expired conversation connections" }
    }
}

internal fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 60_000
    }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
    }
}

internal suspend fun deleteExpiredConnections(
    config: Config,
    client: HttpClient,
): DeleteExpiredActiveConnectionsSummary {
    val token = TexasClient(config.nais.tokenEndpoint, client, logger)
        .getMachineToken(config.api.scope)
        .getOrElse { throw IllegalStateException("Could not obtain cleanup job token (status ${it.code})") }

    val response = client.post("${config.api.url.trimEnd('/')}$CLEANUP_PATH") {
        bearerAuth(token)
    }
    check(response.status.isSuccess()) {
        "Expired connection cleanup failed (HTTP ${response.status.value})"
    }
    return response.body()
}
