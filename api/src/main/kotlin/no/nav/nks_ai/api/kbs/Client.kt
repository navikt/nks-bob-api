package no.nav.nks_ai.api.kbs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.callid.KtorCallIdContextElement
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.retry
import kotlinx.serialization.SerializationException
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.auth.EntraClient
import no.nav.nks_ai.api.defaultJsonConfig

private val logger = KotlinLogging.logger {}

class KbsClient(
    private val baseUrl: String,
    private val sseClient: HttpClient,
    private val entraClient: EntraClient,
    private val scope: String
) {
    fun sendQuestionStream(
        question: String,
        messageHistory: List<KbsChatMessage>,
    ): Flow<Either<KbsErrorResponse, KbsStreamResponse>> = channelFlow {
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
                            val chatResponse = defaultJsonConfig().decodeFromString<KbsStreamResponse.KbsChatResponse>(data.sanitize())
                            if (timer.isRunning && chatResponse.answer.text.isNotEmpty()) {
                                timer.stop()
                            }

                            send(chatResponse.right())
                        }
                    }

                    "status_update" -> {
                        response.data?.let { data ->
                            val statusResponse = defaultJsonConfig().decodeFromString<KbsStreamResponse.StatusUpdateResponse>(data)
                            send(statusResponse.right())
                        }
                    }

                    "error" -> {
                        response.data?.let { data ->
                            try {
                                val errorResponse = defaultJsonConfig().decodeFromString<KbsErrorResponse>(data)
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
    }.retry(3) { throwable ->
        if (throwable.cause is KbsValidationException) {
            logger.warn { "Error when receiving message from KBS. Retrying..." }
            return@retry true
        }

        if (throwable.cause is SSEClientException) {
            logger.warn { "Error when receiving message from KBS (throwable.cause) (${throwable.message}). Retrying..." }
            return@retry true
        }

        if (throwable is SSEClientException) {
            logger.warn { "Error when receiving message from KBS (${throwable.message}). Retrying..." }
            return@retry true
        }

        return@retry false
    }.catch { throwable ->
        val cause = throwable.cause
        when (cause) {
            // bubble the error up
            is KbsValidationException -> emit(cause.toError().left())

            // will be handled somewhere else
            else -> throw throwable
        }
    }
}

private fun String.sanitize(): String = this
    .replace("\\u0000f8", "ø")
    .replace("\\u0000e5", "å")
    .replace("\\u0000e6", "æ")
    .replace("\\u0000", "")