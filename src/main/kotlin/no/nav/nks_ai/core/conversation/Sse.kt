package no.nav.nks_ai.core.conversation

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.sse
import no.nav.nks_ai.core.message.Message
import java.util.Collections
import kotlin.collections.set
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger { }

fun Route.conversationSse(
    conversationService: ConversationService,
) {
    route("/conversations") {
        sse("/{id}/messages/sse", HttpMethod.Get) {
            val navIdent = call.getNavIdent()
                ?: return@sse call.respond(HttpStatusCode.Forbidden)

            val conversationId = call.conversationId()
                ?: return@sse call.respond(HttpStatusCode.BadRequest)
            logger.debug { "SSE connection established for conversation $conversationId" }

            try {
                either {
                    val existingMessages = conversationService.getConversationMessages(conversationId, navIdent).bind()

                    MetricRegister.sseConnections.inc()
                    launch {
                        // Close the connection after a set time
                        delay(2.hours)
                        closeSession(conversationId)
                    }

                    val deferred = async(Dispatchers.IO) {
                        logger.debug { "Sending existing messages for conversation $conversationId" }
                        existingMessages.forEach { message ->
                            send(messageEvent(message))
                        }

                        logger.debug { "Waiting for events for conversation $conversationId" }
                        SseFlowHandler.getFlow(conversationId).asSharedFlow().collect { message ->
                            send(messageEvent(message))
                        }
                    }

                    deferred.await()
                }
            } catch (t: Throwable) {
                logger.error(t) { "Error in SSE session" }
            } finally {
                closeSession(conversationId)
            }
        }
    }
}

private suspend fun ServerSSESession.closeSession(conversationId: ConversationId) {
    logger.debug { "Closing SSE session" }
    close()
    SseFlowHandler.removeFlow(conversationId)
    MetricRegister.sseConnections.dec()
}

private fun messageEvent(message: Message) = ServerSentEvent(
    data = Json.encodeToString(message),
)

object SseFlowHandler {
    private val messageFlows = Collections.synchronizedMap<ConversationId, MutableSharedFlow<Message>>(HashMap())

    fun getFlow(conversationId: ConversationId): MutableSharedFlow<Message> {
        if (messageFlows[conversationId] == null) {
            logger.debug { "Creating new flow for conversation $conversationId" }
            MetricRegister.sharedMessageFlows.inc()
            messageFlows[conversationId] = MutableSharedFlow()
        }
        return messageFlows[conversationId]!!
    }

    fun removeFlow(conversationId: ConversationId) {
        if (messageFlows[conversationId] != null) {
            logger.debug { "Removing flow for conversation $conversationId" }
            MetricRegister.sharedMessageFlows.dec()
            messageFlows.remove(conversationId)
        }
    }
}
