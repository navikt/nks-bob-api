package no.nav.nks_ai.core.conversation

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.plugins.MetricRegister
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.core.message.UpdateMessage
import java.util.Collections
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

            val conversationId = call.conversationId()
                ?: return@webSocket call.respond(HttpStatusCode.BadRequest)

            val existingMessages = conversationService.getConversationMessages(conversationId, navIdent)
                ?: return@webSocket call.respond(HttpStatusCode.NotFound)

            val messageFlow = WebsocketFlowHandler.getFlow(conversationId)
            val job = launch {
                existingMessages.forEach { message ->
                    this@webSocket.sendSerialized(message)
                }

                messageFlow
                    .asSharedFlow()
                    .collect { message ->
                        this@webSocket.sendSerialized(message)
                    }
            }

            runCatching {
                while (true) {
                    val messageEvent = this@webSocket.receiveDeserialized<MessageEvent>()

                    when (messageEvent) {
                        is MessageEvent.NewMessageEvent -> {
                            val newMessage = messageEvent.getData()
                            sendMessageService.sendMessageStream(newMessage, conversationId, navIdent)
                                .let { messageFlow.emitAll(it) }
                        }

                        is MessageEvent.UpdateMessageEvent -> {
                            val updateMessage = messageEvent.getData()
                            logger.debug { "Updating message ${updateMessage.id}" }
                        }

                        is MessageEvent.HeartbeatEvent -> {
                            if (messageEvent.isPing()) {
                                logger.trace { "ping pong" }
                                this@webSocket.send("pong")
                            } else {
                                logger.warn { "Unknown heartbeat message received ${messageEvent.getData()}" }
                            }
                        }

                        else -> {
                            logger.warn { "Unknown event type: ${messageEvent.type}" }
                        }
                    }
                }
            }.onFailure { exception ->
                logger.error(exception) { "Error when listening for websocket events" }
            }.also {
                job.cancel()
                this@webSocket.close()
                WebsocketFlowHandler.removeFlow(conversationId)
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

object WebsocketFlowHandler {
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