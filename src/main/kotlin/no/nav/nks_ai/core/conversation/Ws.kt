package no.nav.nks_ai.core.conversation

import arrow.core.none
import arrow.core.some
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
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.message.Citation
import no.nav.nks_ai.core.message.Context
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageId
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

            MetricRegister.websocketConnections.inc()
            val messageFlow = WebsocketFlowHandler.getFlow(conversationId)
            val job = launch {
                existingMessages.forEach { message ->
                    this@webSocket.sendSerialized<MessageEvent>(
                        MessageEvent.NewMessage(
                            id = message.id,
                            message = message
                        )
                    )
                }

                messageFlow
                    .asSharedFlow()
                    .runningFold(none<Message>()) { prevMessage, message ->
                        prevMessage.onNone {
                            this@webSocket.sendSerialized<MessageEvent>(
                                MessageEvent.NewMessage(
                                    id = message.id,
                                    message = message
                                )
                            )
                        }

                        prevMessage.onSome { prevMessage: Message ->
                            val diff = prevMessage.diff(message)
                            if (diff !is MessageEvent.NoOp) {
                                this@webSocket.sendSerialized<MessageEvent>(diff)
                            }
                        }

                        message.some()
                    }
                    .collect { /* no-op */ }
            }

            runCatching {
                while (true) {
                    val messageAction = this@webSocket.receiveDeserialized<MessageAction>()

                    when (messageAction) {
                        is MessageAction.NewMessageAction -> {
                            val newMessage = messageAction.getData()
                            sendMessageService.sendMessageStream(newMessage, conversationId, navIdent)
                                .let { messageFlow.emitAll(it) }
                        }

                        is MessageAction.UpdateMessageAction -> {
                            val updateMessage = messageAction.getData()
                            logger.debug { "Updating message ${updateMessage.id}" }
                        }

                        is MessageAction.HeartbeatAction -> {
                            if (messageAction.isPing()) {
                                logger.trace { "ping pong" }
                                this@webSocket.send("pong")
                            } else {
                                logger.warn { "Unknown heartbeat message received ${messageAction.getData()}" }
                            }
                        }

                        else -> {
                            logger.warn { "Unknown action type: ${messageAction.type}" }
                        }
                    }
                }
            }.onFailure { exception ->
                logger.error(exception) { "Error when listening for websocket actions" }
            }.also {
                job.cancel()
                this@webSocket.close()
                WebsocketFlowHandler.removeFlow(conversationId)
                MetricRegister.websocketConnections.dec()
            }
        }
    }
}

@Serializable
internal enum class MessageActionType {
    NewMessage,
    UpdateMessage,
    Heartbeat,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
internal sealed class MessageAction {
    abstract val type: MessageActionType
    protected abstract val data: JsonElement

    @Serializable
    @SerialName("NewMessage")
    data class NewMessageAction(
        override val type: MessageActionType = MessageActionType.NewMessage,
        override val data: JsonElement
    ) : MessageAction() {
        fun getData(): NewMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("UpdateMessage")
    data class UpdateMessageAction(
        override val type: MessageActionType = MessageActionType.UpdateMessage,
        override val data: JsonElement
    ) : MessageAction() {
        fun getData(): UpdateMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("Heartbeat")
    data class HeartbeatAction(
        override val type: MessageActionType = MessageActionType.Heartbeat,
        override val data: JsonElement
    ) : MessageAction() {
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

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
internal sealed class MessageEvent() {
    @Serializable
    @SerialName("NewMessage")
    data class NewMessage(
        val id: MessageId,
        val message: Message
    ) : MessageEvent()

    @Serializable
    @SerialName("ContentUpdated")
    data class ContentUpdated(
        val id: MessageId,
        val content: String,
    ) : MessageEvent()

    @Serializable
    @SerialName("CitationsUpdated")
    data class CitationsUpdated(
        val id: MessageId,
        val citations: List<Citation>,
    ) : MessageEvent()

    @Serializable
    @SerialName("ContextUpdated")
    data class ContextUpdated(
        val id: MessageId,
        val context: List<Context>,
    ) : MessageEvent()

    @Serializable
    @SerialName("PendingUpdated")
    data class PendingUpdated(
        val id: MessageId,
        val message: Message,
        val pending: Boolean,
    ) : MessageEvent()

    class NoOp : MessageEvent()
}

private fun Message.diff(message: Message): MessageEvent {
    if (this.id != message.id) {
        return MessageEvent.NewMessage(
            id = message.id,
            message = message
        )
    }

    if (this.content != message.content) {
        return MessageEvent.ContentUpdated(
            id = message.id,
            content = message.content.removePrefix(this.content)
        )
    }

    if (this.citations != message.citations) {
        return MessageEvent.CitationsUpdated(
            id = message.id,
            citations = message.citations
        )
    }

    if (this.context != message.context) {
        return MessageEvent.ContextUpdated(
            id = message.id,
            context = message.context
        )
    }

    if (this.pending != message.pending) {
        return MessageEvent.PendingUpdated(
            id = message.id,
            message = message,
            pending = message.pending,
        )
    }

    return MessageEvent.NoOp()
}