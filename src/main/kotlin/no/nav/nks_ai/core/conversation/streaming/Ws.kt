package no.nav.nks_ai.core.conversation.streaming

import arrow.core.none
import arrow.core.some
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.callid.withCallId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.conversation.conversationId
import no.nav.nks_ai.core.message.Message
import java.util.Collections
import kotlin.collections.set

private val logger = KotlinLogging.logger {}

fun Route.webSocketWithCallId(
    path: String,
    protocol: String? = null,
    handler: suspend DefaultWebSocketServerSession.() -> Unit
) = webSocket(path, protocol) {
    val callId = call.request.header("nav-call-id")
        ?: return@webSocket call.respond(HttpStatusCode.BadRequest)

    withCallId(callId) {
        handler()
    }
}

fun Route.conversationWebsocket(
    conversationService: ConversationService,
    sendMessageService: SendMessageService,
) {
    route("/conversations") {
        webSocketWithCallId("/{id}/messages/ws") {
            val navIdent = call.getNavIdent()
                ?: return@webSocketWithCallId call.respond(HttpStatusCode.Forbidden)

            val conversationId = call.conversationId()
                ?: return@webSocketWithCallId call.respond(HttpStatusCode.BadRequest)

            val existingMessages = conversationService.getConversationMessages(conversationId, navIdent)
                ?: return@webSocketWithCallId call.respond(HttpStatusCode.NotFound)

            MetricRegister.websocketConnections.inc()
            val messageFlow = WebsocketFlowHandler.getFlow(conversationId)
            val job = launch {
                existingMessages.forEach { message ->
                    sendSerialized<ConversationEvent>(
                        ConversationEvent.NewMessage(
                            id = message.id,
                            message = message
                        )
                    )
                }

                messageFlow
                    .asSharedFlow()
                    .runningFold(none<Message>()) { prevMessage, message ->
                        prevMessage.onNone {
                            sendSerialized<ConversationEvent>(
                                ConversationEvent.NewMessage(
                                    id = message.id,
                                    message = message
                                )
                            )
                        }

                        prevMessage.onSome { prevMessage: Message ->
                            val diff = prevMessage.diff(message)
                            if (diff !is ConversationEvent.NoOp) {
                                sendSerialized<ConversationEvent>(diff)
                            }
                        }

                        message.some()
                    }
                    .collect { /* no-op */ }
            }

            runCatching {
                while (true) {
                    val conversationAction = receiveDeserialized<ConversationAction>()

                    when (conversationAction) {
                        is ConversationAction.NewMessageAction -> {
                            val newMessage = conversationAction.getData()
                            sendMessageService.sendMessageStream(newMessage, conversationId, navIdent)
                                .let { messageFlow.emitAll(it) }
                        }

                        is ConversationAction.UpdateMessageAction -> {
                            val updateMessage = conversationAction.getData()
                            logger.debug { "Updating message ${updateMessage.id}" }
                        }

                        is ConversationAction.HeartbeatAction -> {
                            if (conversationAction.isPing()) {
                                logger.trace { "ping pong" }
                                send("pong")
                            } else {
                                logger.warn { "Unknown heartbeat message received ${conversationAction.getData()}" }
                            }
                        }

                        else -> {
                            logger.warn { "Unknown action type: ${conversationAction.type}" }
                        }
                    }
                }
            }.onFailure { exception ->
                when (exception) {
                    is ClosedReceiveChannelException ->
                        logger.info { "Closing websocket connection for conversation $conversationId" }

                    else ->
                        logger.error(exception) { "Error when listening for websocket actions" }
                }
            }.also {
                job.cancel()
                close()
                WebsocketFlowHandler.removeFlow(conversationId)
                MetricRegister.websocketConnections.dec()
            }
        }
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