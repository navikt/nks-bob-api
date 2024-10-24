package no.nav.nks_ai.core.conversation

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.sse
import no.nav.nks_ai.core.message.Message
import java.util.Collections
import java.util.UUID
import kotlin.collections.set

private val logger = KotlinLogging.logger { }

fun Route.conversationSse(
    conversationService: ConversationService,
) {
    route("/conversations") {
        sse("/{id}/messages/sse", HttpMethod.Get) {
            logger.debug { "SSE connection opened" }
            val navIdent = call.getNavIdent()
                ?: return@sse call.respond(HttpStatusCode.Forbidden)

            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@sse call.respond(HttpStatusCode.BadRequest)
            logger.debug { "SSE connection established for conversation $conversationId" }

            val existingMessages = conversationService.getConversationMessages(conversationId, navIdent)
                ?: return@sse call.respond(HttpStatusCode.NotFound)

            val deferred = async(Dispatchers.IO) {
                logger.debug { "Sending existing messages for conversation $conversationId" }
                existingMessages.forEach { message ->
                    send(messageEvent(message))
                }

                logger.debug { "Waiting for events for conversation $conversationId" }
                SseChannelHandler.getFlow(conversationId).asSharedFlow().collect { message ->
                    send(messageEvent(message))
                }
            }

            deferred.await()
            logger.debug { "Closing SSE session" }
            close()
        }
    }
}

private fun messageEvent(message: Message) = ServerSentEvent(
    data = Json.encodeToString(message),
//    event = "MessageEvent",
//    id = UUID.randomUUID().toString(),
//    retry = 100
)

object SseChannelHandler {
    private val messageFlows = Collections.synchronizedMap<UUID, MutableSharedFlow<Message>>(HashMap())

    fun getFlow(conversationId: UUID): MutableSharedFlow<Message> {
        if (messageFlows[conversationId] == null) {
            logger.debug { "Creating new flow for conversation $conversationId" }
            messageFlows[conversationId] = MutableSharedFlow()
        }
        return messageFlows[conversationId]!!
    }

    private val channels = Collections.synchronizedMap<UUID, Channel<Message>>(HashMap())

    fun getChannel(conversationId: UUID): Channel<Message> {
        if (channels[conversationId] == null) {
            logger.debug { "Creating new channel for conversation $conversationId" }
            channels[conversationId] =
                Channel<Message>(
                    capacity = Channel.UNLIMITED,
                    onUndeliveredElement = { message ->
                        logger.error { "message ${message.id} was not delivered" }
                    }
                )
        }
        return channels[conversationId]!!
    }
}
