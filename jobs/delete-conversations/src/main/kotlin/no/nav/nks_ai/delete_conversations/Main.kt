package no.nav.nks_ai.delete_conversations

import arrow.core.getOrElse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.getAs
import kotlinx.serialization.json.Json
import no.nav.nks_ai.shared.DeleteOldConversationsSummary
import no.nav.nks_ai.shared.ErrorResponse
import no.nav.nks_ai.shared.auth.TexasClient
import java.util.UUID

private val logger = KotlinLogging.logger {}

private val appConfig: Config by lazy { ApplicationConfig("application.conf").getAs<Config>() }

/** Kun for tester — overstyr config uten å påvirke produksjonens lazy-initialisering. */
internal var testConfigOverride: Config? = null

fun getConfig(): Config = testConfigOverride ?: appConfig

suspend fun main() {
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(CallId) {
            generate { UUID.randomUUID().toString() }
        }
    }

    val config = getConfig()

    val texasClient = TexasClient(
        naisTokenEndpoint = config.nais.tokenEndpoint,
        httpClient = httpClient,
        logger = logger,
    )

    val token = texasClient.getMachineToken(config.api.scope)
        .getOrElse { throw IllegalStateException("${it.message}: ${it.description}") }

    logger.info { "Deleting old conversations" }
    val response = httpClient.post("${config.api.url}/api/v1/admin/jobs/delete-old-conversations") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    if (!response.status.isSuccess()) {
        val error = response.body<ErrorResponse>()
        logger.error { "Error from delete-old-conversations: ${error.code} ${error.message}" }
        throw IllegalStateException(
            "delete-old-conversations job failed with status ${response.status}: ${error.code} ${error.message}"
        )
    }

    val result = response.body<DeleteOldConversationsSummary>()
    logger.info { "Deleted ${result.deletedConversations} conversations and ${result.deletedMessages} messages" }
}
