package no.nav.nks_ai.kbs

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import no.nav.nks_ai.auth.EntraClient
import no.nav.nks_ai.core.message.Context
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.NewCitation

@Serializable
data class KbsChatRequest(
    val question: String,
    val history: List<KbsChatMessage>,
)

@Serializable
enum class KbsMessageRole {
    @SerialName("human")
    Human,

    @SerialName("ai")
    AI,
}

fun KbsMessageRole.Companion.fromMessageRole(messageRole: MessageRole): KbsMessageRole =
    when (messageRole) {
        MessageRole.Human -> KbsMessageRole.Human
        MessageRole.AI -> KbsMessageRole.AI
    }

fun KbsChatMessage.Companion.fromMessage(message: Message): KbsChatMessage = KbsChatMessage(
    content = message.content,
    role = KbsMessageRole.fromMessageRole(message.messageRole)
)

@Serializable
data class KbsChatMessage(
    val content: String,
    val role: KbsMessageRole,
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

fun KbsCitation.toNewCitation() =
    NewCitation(
        text = text,
        article = article,
        title = title,
        section = section,
    )

@Serializable
data class KbsChatContext(
    val content: String,
    val metadata: JsonObject
)

fun KbsChatContext.toModel(): Context =
    Context(
        content = content,
        metadata = metadata,
    )

private val logger = KotlinLogging.logger {}

class KbsClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val sseClient: HttpClient,
    private val entraClient: EntraClient,
    private val scope: String
) {
    suspend fun sendQuestion(
        question: String,
        messageHistory: List<KbsChatMessage>,
    ): KbsChatResponse? {
        val token = entraClient.getMachineToken(scope)

        val response = httpClient.post("$baseUrl/api/v1/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(KbsChatRequest(question, messageHistory))
        }

        if (!response.status.isSuccess()) {
            logger.error { "Error sending message to KBS: ${response.status.description}" }
            return null // TODO proper error handling
        }
        return response.body()
    }

    fun sendQuestionStream(
        question: String,
        messageHistory: List<KbsChatMessage>,
    ): Flow<KbsChatResponse> = channelFlow {
        val token = entraClient.getMachineToken(scope)

        sseClient.sse("$baseUrl/api/v1/stream/chat", {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(KbsChatRequest(question, messageHistory))
        }) {
            incoming.collect { response ->
                when (response.event) {
                    "chat_chunk" -> {
                        response.data?.let { data ->
                            send(Json.decodeFromString<KbsChatResponse>(data))
                        }
                    }

                    else -> {
                        logger.debug { "Unknown event received ${response.event}" }
                    }
                }
            }
        }
    }
}