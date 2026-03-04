package no.nav.nks_ai.delete_conversations

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
import kotlinx.serialization.json.Json
import no.nav.nks_ai.shared.DeleteOldConversationsSummary
import no.nav.nks_ai.shared.ErrorResponse
import no.nav.nks_ai.shared.auth.EntraClient
import java.util.UUID

val logger = KotlinLogging.logger {}

suspend fun main() {
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        install(CallId) {
            generate { UUID.randomUUID().toString() }
        }
    }

    val entraClient = EntraClient(
        entraTokenUrl = Config.jwt.configTokenEndpoint,
        clientId = Config.jwt.clientId,
        clientSecret = Config.jwt.clientSecret,
        httpClient = httpClient,
        logger = logger,
    )

    val token = entraClient.getMachineToken(Config.api.scope)
    logger.info { "Deleting old conversations" }
    val response = httpClient.post("${Config.api.url}/api/v1/admin/jobs/delete-old-conversations") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    if (!response.status.isSuccess()) {
        val error = response.body<ErrorResponse>()
        logger.error { "Error from delete-old-conversations: ${error.code} ${error.message}" }
        return
    }

    val result = response.body<DeleteOldConversationsSummary>()
    logger.info { "Deleted ${result.deletedConversations} conversations and ${result.deletedMessages} messages" }
}