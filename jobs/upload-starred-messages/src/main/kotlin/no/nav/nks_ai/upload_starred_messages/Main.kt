package no.nav.nks_ai.upload_starred_messages

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
import no.nav.nks_ai.shared.ErrorResponse
import no.nav.nks_ai.shared.UploadStarredMessagesSummary
import no.nav.nks_ai.shared.auth.TexasClient
import java.util.UUID

private val logger = KotlinLogging.logger {}

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

    val texasClient = TexasClient(
        naisTokenEndpoint = Config.nais.tokenEndpoint,
        httpClient = httpClient,
        logger = logger,
    )

    val token = texasClient.getMachineToken(Config.api.scope)
        .onLeft { throw IllegalStateException("Could not get machine token: ${it.message}") }
        .getOrNull()!!

    logger.info { "Uploading starred messages" }
    val response = httpClient.post("${Config.api.url}/api/v1/admin/jobs/upload-starred-messages") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    if (!response.status.isSuccess()) {
        val error = response.body<ErrorResponse>()
        logger.error { "Error from upload-starred-messages: ${error.code} ${error.message}" }
        throw IllegalStateException(
            "upload-starred-messages job failed with status ${response.status}: ${error.code} ${error.message}"
        )
    }

    val result = response.body<UploadStarredMessagesSummary>()
    logger.info { "Uploaded ${result.uploadedMessages} messages" }

    if(result.errors.isNotEmpty()) {
        logger.info { "Errors: ${result.errors}" }
    }
}
