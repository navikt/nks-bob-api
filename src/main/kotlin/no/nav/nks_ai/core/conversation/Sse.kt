package no.nav.nks_ai.core.conversation

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

            launch(Dispatchers.IO) {
                logger.debug { "Sending existing messages for conversation $conversationId" }
                existingMessages.forEach { message ->
                    send(data = Json.encodeToString(message))
                }

                val channel = SseChannelHandler.getChannel(conversationId)
                logger.debug { "Waiting for events for conversation $conversationId" }
                for (message in channel) {
                    send(
                        data = Json.encodeToString(message),
                    )
                }
            }
        }
    }
}

object SseChannelHandler {
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

object SseSessionHandler {
    private val sessions = Collections.synchronizedMap<UUID, MutableList<ServerSSESession>>(HashMap())

    suspend fun closeAllSessions() {
        logger.debug { "Closing all (${sessionCount()}) SSE sessions" }
        sessions.forEach { entry ->
            entry.value.forEach { session ->
                session.close()
                removeSession(entry.key, session)
            }
        }
    }

    internal fun addSession(conversationId: UUID, session: ServerSSESession) {
        if (sessions[conversationId] == null) {
            sessions[conversationId] = Collections.synchronizedList(ArrayList())
        }
        sessions[conversationId]!!.add(session)
        logger.debug { "SSE session added. Active sessions: ${sessionCount()}" }
    }

    private fun removeSession(conversationId: UUID, session: ServerSSESession) {
        if (sessions[conversationId] == null) {
            sessions[conversationId] = Collections.synchronizedList(ArrayList())
            return
        }
        sessions[conversationId]!!.remove(session)
        logger.debug { "SSE session removed. Active sessions: ${sessionCount()}" }
    }

    private fun sessionCount() = sessions.flatMap { it.value }.count()
}
