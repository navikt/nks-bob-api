package no.nav.nks_ai.kbs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.callid.KtorCallIdContextElement
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.retry
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.auth.EntraClient
import no.nav.nks_ai.core.message.Context
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.NewCitation
import kotlin.String

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
    val context: List<KbsChatContext>,
    @SerialName("follow_up") val followUp: List<String> = emptyList(),
)

@Serializable
data class KbsChatAnswer(
    val text: String,
    val citations: List<KbsCitation>
)

@Serializable
data class KbsCitation(
    val text: String,
    @SerialName("source_id") val sourceId: Int,
)

fun KbsCitation.toNewCitation() =
    NewCitation(
        text = text,
        sourceId = sourceId,
    )

@Serializable
data class KbsChatContext(
    val content: String,
    val title: String,
    val ingress: String,
    val source: String,
    val url: String,
    val anchor: String?,
    @SerialName("article_id") val articleId: String,
    @SerialName("article_column") val articleColumn: String?,
    @SerialName("last_modified") val lastModified: LocalDateTime?,
    @SerialName("semantic_similarity") val semanticSimilarity: Double,
)

fun KbsChatContext.toModel(): Context =
    Context(
        content = content,
        title = title,
        ingress = ingress,
        source = source,
        url = url,
        anchor = anchor,
        articleId = articleId,
        articleColumn = articleColumn,
        lastModified = lastModified,
        semanticSimilarity = semanticSimilarity,
    )

@Serializable
sealed class KbsErrorResponse {
    abstract val title: String
    abstract val detail: String

    @OptIn(ExperimentalSerializationApi::class)
    @JsonClassDiscriminator("type")
    @Serializable
    sealed class KbsTypedError : KbsErrorResponse() {
        abstract val type: KbsErrorType
        abstract val status: Int

        @Serializable
        @SerialName(VALIDATION_ERROR_NAME)
        data class KbsValidationError(
            override val type: KbsErrorType = KbsErrorType.ValidationError,
            override val status: Int,
            override val title: String,
            override val detail: String,
        ) : KbsTypedError()

        @Serializable
        @SerialName(MODEL_ERROR_NAME)
        data class KbsModelError(
            override val type: KbsErrorType = KbsErrorType.ModelError,
            override val status: Int,
            override val title: String,
            override val detail: String,
        ) : KbsTypedError()
    }

    @Serializable
    data class KbsGenericError(
        override val detail: String
    ) : KbsErrorResponse() {
        override val title: String
            get() = "Unknown error"
    }
}

private const val VALIDATION_ERROR_NAME = "urn:nks-kbs:error:validation"
private const val MODEL_ERROR_NAME = "urn:nks-kbs:error:model"

@Serializable
enum class KbsErrorType {
    @SerialName(VALIDATION_ERROR_NAME)
    ValidationError,

    @SerialName(MODEL_ERROR_NAME)
    ModelError,
}

internal data class KbsValidationException(
    val status: Int,
    val title: String,
    val detail: String,
) : Throwable() {
    fun toError() =
        KbsErrorResponse.KbsTypedError.KbsValidationError(
            status = status,
            title = title,
            detail = detail,
        )

    companion object {
        fun fromError(error: KbsErrorResponse.KbsTypedError.KbsValidationError) =
            KbsValidationException(
                status = error.status,
                title = error.title,
                detail = error.detail,
            )
    }
}

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
    ): Flow<Either<KbsErrorResponse, KbsChatResponse>> = channelFlow {
        val token = entraClient.getMachineToken(scope)

        sseClient.sse("$baseUrl/api/v1/stream/chat", {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(KbsChatRequest(question, messageHistory))

            // FIXME: Manually setting callId-header. Possibly a bug in Ktor SSE client.
            coroutineContext[KtorCallIdContextElement.Companion]?.callId
                ?.let { callId -> header(HttpHeaders.XRequestId, callId) }
                ?: logger.warn { "Could not find callId when sending message to KBS" }
        }) {
            val timer = MetricRegister.answerFirstContentReceived()
            incoming.collect { response ->
                when (response.event) {
                    "chat_chunk" -> {
                        response.data?.let { data ->
                            val chatResponse = Json.decodeFromString<KbsChatResponse>(data)
                            if (timer.isRunning && chatResponse.answer.text.isNotEmpty()) {
                                timer.stop()
                            }

                            send(chatResponse.right())
                        }
                    }

                    "error" -> {
                        response.data?.let { data ->
                            try {
                                val errorResponse = Json.decodeFromString<KbsErrorResponse>(data)
                                when (errorResponse) {
                                    is KbsErrorResponse.KbsTypedError.KbsValidationError -> {
                                        logger.warn {
                                            """
                                              Validation error received from KBS:  
                                              ${errorResponse.title}
                                              
                                              ${errorResponse.detail}
                                            """.trimIndent()
                                        }

                                        // Will trigger a retry
                                        throw KbsValidationException.fromError(errorResponse)
                                    }

                                    else -> send(errorResponse.left())
                                }

                            } catch (illegalArgumentException: IllegalArgumentException) {
                                logger.error(illegalArgumentException) {
                                    "Unknown error type received from KBS: $data"
                                }
                            } catch (serializationException: SerializationException) {
                                logger.error(serializationException) {
                                    "Error when decoding error from KBS: $data"
                                }
                            }
                        }
                    }

                    else -> {
                        logger.error { "Unknown event received ${response.event}" }
                    }
                }
            }
        }
    }.retry(2) { throwable ->
        if (throwable.cause is KbsValidationException) {
            logger.warn { "Error when receiving message from KBS. Retrying..." }
            return@retry true
        }

        return@retry false
    }
        .catch { throwable ->
            val cause = throwable.cause
            when (cause) {
                // bubble the error up
                is KbsValidationException -> emit(cause.toError().left())

                // will be handled somewhere else
                else -> throw throwable
            }
        }
}