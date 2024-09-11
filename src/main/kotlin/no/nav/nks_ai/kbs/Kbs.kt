package no.nav.nks_ai.kbs

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable
data class KbsChatRequest(
    val question: String,
    val history: List<String>,
)

@Serializable
data class KbsChatResponse(
    val answer: KbsChatAnswer,
    val context: List<KbsChatContext>
)

@Serializable
data class KbsChatAnswer(
    val text: String,
    val citations: List<KbsCitation>
)

@Serializable
data class KbsCitation(
    val text: String,
    val article: String,
    val title: String,
    val section: String,
)

@Serializable
data class KbsChatContext(
    val content: String,
//    val metadata: Map<String, Any>
)

val logger = KotlinLogging.logger {}

class KbsClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
    suspend fun sendQuestion(
        token: String,
        question: String,
        messageHistory: List<String>,
    ): KbsChatResponse? {
//        val token = azureAdTokenClient.getMachineToMachineToken(scope)

        val response = httpClient.post("$baseUrl/api/v1/chat") {
            header(HttpHeaders.Authorization, token)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(KbsChatRequest(question, messageHistory))
        }

        if (!response.status.isSuccess()) {
            logger.error { "Error sending message to KBS: ${response.status.description}" }
            return null // TODO proper error handling
        }
        return response.body()
    }
}