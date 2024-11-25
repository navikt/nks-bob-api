package no.nav.nks_ai.core.conversation.streaming

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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationService
import java.util.Collections
import java.util.UUID
import kotlin.collections.set

private val logger = KotlinLogging.logger {}

fun Route.conversationWebsocket(
    conversationService: ConversationService,
    sendMessageService: SendMessageService,
) {
    route("/conversations") {
        webSocket("/ws") {
            val navIdent = call.getNavIdent()
                ?: return@webSocket call.respond(HttpStatusCode.Forbidden)

            val sessionId = WebsocketSessionId()

            MetricRegister.websocketConnections.inc()
            val eventFlow = WebsocketFlowHandler.getEventFlow(sessionId)

            val eventsListener = launch {
                eventFlow.asSharedFlow().collect { event ->
                    sendSerialized<ConversationEvent>(event)
                }
            }

            runCatching {
                while (true) {
                    val conversationAction = receiveDeserialized<ConversationAction>()

                    when (conversationAction) {
                        is ConversationAction.NewMessageAction -> {
                            val payload = conversationAction.getData()
                            val flows = WebsocketFlowHandler.getSubscribedSessions(payload.conversationId)

                            sendMessageService.sendMessageWithEvents(
                                message = payload.asNewMessage(),
                                conversationId = payload.conversationId,
                                navIdent = navIdent
                            ).let { events ->
                                flows.forEach { it.emitAll(events) }
                            }
                        }

                        is ConversationAction.CreateConversationAction -> {
                            val payload = conversationAction.getData()
                            val conversation =
                                conversationService.addConversation(navIdent, payload.asNewConversation())
                            sendSerialized<ConversationEvent>(ConversationEvent.ConversationCreated(conversation))

                            if (payload.subscribe) {
                                WebsocketFlowHandler.subscribeToConversation(sessionId, conversation.id)
                            }

                            if (payload.initialMessage != null) {
                                val flows = WebsocketFlowHandler.getSubscribedSessions(conversationId = conversation.id)

                                sendMessageService.sendMessageWithEvents(
                                    message = payload.initialMessage,
                                    conversationId = conversation.id,
                                    navIdent = navIdent
                                ).let { events ->
                                    flows.forEach { it.emitAll(events) }
                                }
                            }
                        }

                        is ConversationAction.SubscribeToConversationAction -> {
                            val conversationId = conversationAction.getData().conversationId
                            WebsocketFlowHandler.subscribeToConversation(sessionId, conversationId)

                            // Send existing messages
                            conversationService.getConversationMessages(conversationId, navIdent)
                                ?.let { existingMessages ->
                                    WebsocketFlowHandler.getEventFlow(sessionId)
                                        .emitAll(existingMessages
                                            .asFlow()
                                            .map { message ->
                                                ConversationEvent.NewMessage(
                                                    id = message.id,
                                                    message = message
                                                )
                                            })
                                }
                        }

                        is ConversationAction.UnsubscribeAllConversationsAction -> {
                            WebsocketFlowHandler.unsubscribe(sessionId)
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
                logger.error(exception) { "Error when listening for websocket actions" }
            }.also {
                eventsListener.cancel()
                close()
                WebsocketFlowHandler.removeEventFlow(sessionId)
                MetricRegister.websocketConnections.dec()
            }
        }
    }
}

@JvmInline
value class WebsocketSessionId(val value: UUID = UUID.randomUUID())

object WebsocketFlowHandler {
    private val eventFlows =
        Collections.synchronizedMap<WebsocketSessionId, MutableSharedFlow<ConversationEvent>>(HashMap())

    private val subscribedSessions = Collections.synchronizedMap<WebsocketSessionId, ConversationId>(HashMap())

    internal fun getEventFlow(sessionId: WebsocketSessionId): MutableSharedFlow<ConversationEvent> {
        if (eventFlows[sessionId] == null) {
            logger.debug { "Creating new flow for session $sessionId" }
            MetricRegister.sharedMessageFlows.inc()
            eventFlows[sessionId] = MutableSharedFlow()
        }
        return eventFlows[sessionId]!!
    }

    internal fun removeEventFlow(sessionId: WebsocketSessionId) {
        if (eventFlows[sessionId] != null) {
            logger.debug { "Removing flow for session $sessionId" }
            MetricRegister.sharedMessageFlows.dec()
            eventFlows.remove(sessionId)
        }
    }

    internal fun getSubscribedSessions(conversationId: ConversationId): List<MutableSharedFlow<ConversationEvent>> {
        return subscribedSessions.filter { it.value == conversationId }
            .map { entry -> getEventFlow(entry.key) }
    }

    internal fun subscribeToConversation(sessionId: WebsocketSessionId, conversationId: ConversationId) {
        subscribedSessions[sessionId] = conversationId
    }

    internal fun unsubscribe(sessionId: WebsocketSessionId) {
        subscribedSessions.remove(sessionId)
    }
}