package no.nav.nks_ai.core.conversation

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.CancellationException
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.core.message.UpdateMessage
import java.util.Collections
import java.util.UUID
import kotlin.collections.set

private val logger = KotlinLogging.logger {}

fun Route.conversationWebsocket(
    conversationService: ConversationService,
    sendMessageService: SendMessageService,
) {
    route("/conversations") {
        webSocket("/{id}/messages/ws") {
            val navIdent = call.getNavIdent()
                ?: return@webSocket call.respond(HttpStatusCode.Forbidden)

            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@webSocket call.respond(HttpStatusCode.BadRequest)

            val existingMessages = conversationService.getConversationMessages(conversationId, navIdent)
                ?: return@webSocket call.respond(HttpStatusCode.NotFound)

            try {
                WebsocketSessionHandler.addSession(conversationId, this)

                existingMessages.forEach { message ->
                    sendSerialized(message)
                }

                while (true) {
                    val messageEvent = receiveDeserialized<MessageEvent>()

                    when (messageEvent) {
                        is MessageEvent.NewMessageEvent -> {
                            val newMessage = messageEvent.getData()
                            val channel = sendMessageService.sendMessageChannel(newMessage, conversationId, navIdent)
                            for (message in channel) {
                                for (session in WebsocketSessionHandler.getSessions(conversationId)) {
                                    session.sendSerialized(message)
                                }
                            }
                        }

                        is MessageEvent.UpdateMessageEvent -> {
                            val updateMessage = messageEvent.getData()
                            logger.debug { "Updating message ${updateMessage.id}" }
                        }

                        is MessageEvent.HeartbeatEvent -> {
                            if (messageEvent.isPing()) {
                                logger.trace { "ping pong" }
                                send("pong")
                            } else {
                                logger.warn { "Unknown heartbeat message received ${messageEvent.getData()}" }
                            }
                        }

                        else -> {
                            logger.warn { "Unknown event type: ${messageEvent.type}" }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                logger.debug { "onClose: ${e.message}" }
            } catch (e: CancellationException) {
                logger.debug { "onCancel: ${e.message}" }
            } catch (e: Throwable) {
                logger.warn { "Error when closing websocket connection: ${e.message}" }
            } finally {
                WebsocketSessionHandler.removeSession(conversationId, this)
            }
        }
    }
}

@Serializable
internal enum class MessageEventType {
    NewMessage,
    UpdateMessage,
    Heartbeat,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
internal sealed class MessageEvent {
    abstract val type: MessageEventType
    protected abstract val data: JsonElement

    @Serializable
    @SerialName("NewMessage")
    data class NewMessageEvent(
        override val type: MessageEventType = MessageEventType.NewMessage,
        override val data: JsonElement
    ) : MessageEvent() {
        fun getData(): NewMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("UpdateMessage")
    data class UpdateMessageEvent(
        override val type: MessageEventType = MessageEventType.UpdateMessage,
        override val data: JsonElement
    ) : MessageEvent() {
        fun getData(): UpdateMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("Heartbeat")
    data class HeartbeatEvent(
        override val type: MessageEventType = MessageEventType.Heartbeat,
        override val data: JsonElement
    ) : MessageEvent() {
        fun getData(): String = Json.decodeFromJsonElement(data)

        fun isPing() = getData() == "ping"
    }
}

private object WebsocketSessionHandler {
    private val sessions = Collections.synchronizedMap<UUID, MutableList<WebSocketServerSession>>(HashMap())

    fun getSessions(conversationId: UUID): MutableList<WebSocketServerSession> {
        if (sessions[conversationId] == null) {
            sessions[conversationId] = Collections.synchronizedList(ArrayList())
        }
        return sessions[conversationId]!!
    }

    fun addSession(conversationId: UUID, session: WebSocketServerSession) {
        if (sessions[conversationId] == null) {
            sessions[conversationId] = Collections.synchronizedList(ArrayList())
        }
        sessions[conversationId]!!.add(session)
        logger.debug { "Websocket session added. Active sessions: ${sessions.size}" }
    }

    fun removeSession(conversationId: UUID, session: WebSocketServerSession) {
        if (sessions[conversationId] == null) {
            sessions[conversationId] = Collections.synchronizedList(ArrayList())
            return
        }
        sessions[conversationId]!!.remove(session)
        logger.debug { "Websocket session removed. Active sessions: ${sessions.size}" }
    }
}
