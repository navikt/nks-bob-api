package no.nav.nks_ai.api.vaskemaskin

import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.shared.auth.TexasClient

private val logger = KotlinLogging.logger {}

class VaskemaskinClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val texasClient: TexasClient,
    private val targetAudience: String,
) {
    suspend fun detect(text: String): ApplicationResult<Boolean> {
        return try {
            val token = texasClient.getMachineToken(targetAudience)
            val response = httpClient.post("$baseUrl/detect") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(AnonymizeRequest(text))
            }

            if (!response.status.isSuccess()) {
                logger.error { "Vaskemaskin returned error status ${response.status}" }
                return ApplicationError.InternalServerError(
                    message = "PII detection failed",
                    description = "Vaskemaskin returned status ${response.status}",
                ).left()
            }

            response.body<DetectResponse>().containsPii.right()
        } catch (e: Exception) {
            logger.error(e) { "Error calling vaskemaskin" }
            ApplicationError.InternalServerError(
                message = "PII detection failed",
                description = e.message ?: "Unknown error when calling vaskemaskin",
            ).left()
        }
    }

    suspend fun anonymize(text: String): ApplicationResult<String> {
        return try {
            val token = texasClient.getMachineToken(targetAudience)
            val response = httpClient.post("$baseUrl/anonymize") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(AnonymizeRequest(text))
            }

            if (!response.status.isSuccess()) {
                logger.error { "Vaskemaskin returned error status ${response.status}" }
                return ApplicationError.InternalServerError(
                    message = "PII cleaning failed",
                    description = "Vaskemaskin returned status ${response.status}",
                ).left()
            }

            response.body<AnonymizeResponse>().text.right()
        } catch (e: Exception) {
            logger.error(e) { "Error calling vaskemaskin" }
            ApplicationError.InternalServerError(
                message = "PII cleaning failed",
                description = e.message ?: "Unknown error when calling vaskemaskin",
            ).left()
        }
    }
}

@Serializable
data class AnonymizeRequest(
    val text: String,
)

@Serializable
data class AnonymizeResponse(
    val text: String,
    val entities: List<Entity>? = null,
)

@Serializable
data class DetectResponse(
    @SerialName("contains_pii")
    val containsPii: Boolean,
    val entities: List<Entity>? = null,
)

@Serializable
data class Entity(
    val text: String,
    val label: String,
    val start: Int,
    val end: Int,
)
