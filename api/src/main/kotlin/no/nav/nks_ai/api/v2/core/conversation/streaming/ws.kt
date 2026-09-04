package no.nav.nks_ai.api.v2.core.conversation.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.app.getNavIdent
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.conversation.conversationId

private val logger = KotlinLogging.logger { }

/**
 * Websocket endpoint that streams [ConversationEvent]s for a single conversation to the client.
 *
 * Unlike the old POST-SSE endpoint (see Sse.kt), this connection is not tied to whichever
 * instance is currently talking to KBS: every event produced anywhere (including by a third-party
 * service creating a message directly against KBS) is published to a shared Kafka topic by
 * [ConversationEventBus], and every running instance consumes that topic. Whichever instance
 * happens to hold the client's websocket connection simply filters the shared, in-process
 * [ConversationEventBus.events] flow down to the current conversation id and forwards matching
 * events to the client. This is what makes the feature work with multiple running instances.
 *
 * On connect, the client is first sent every event recorded so far for the conversation (see
 * [ConversationEventBus.history]), before switching over to forwarding new events live. This
 * covers the frontend being out of sync with the backend - e.g. it navigated away mid-answer and
 * back, or has a slow/flaky connection - since it can otherwise miss chunks that were only ever
 * sent once. The frontend is expected to filter out events it has already applied.
 */
fun Route.conversationWebSocketV2(
    conversationService: ConversationService,
    conversationEventBus: ConversationEventBus,
) {
    route("/conversations") {
        webSocket("/{id}/messages/ws") {
            val navIdent = call.getNavIdent()
            if (navIdent == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing NAVident"))
                return@webSocket
            }

            val conversationId = call.conversationId().getOrNull()
            if (conversationId == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing conversation id"))
                return@webSocket
            }

            // Reuse the same ownership check as the REST endpoints for this conversation, so a
            // user can't subscribe to another user's conversation just by knowing its id.
            val hasAccess = conversationService.getConversation(conversationId, navIdent).isRight()
            if (!hasAccess) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Conversation not found"))
                return@webSocket
            }

            logger.debug { "Websocket connection established for conversation $conversationId" }
            MetricRegister.websocketConnections.inc()

            try {
                coroutineScope {
                    // This is a server-push-only endpoint: the client isn't expected to send any
                    // application data. We still need to drain `incoming` so we notice when the
                    // client disconnects (closes the tab, loses network, etc.), and cancel the
                    // event forwarding below when that happens.
                    val incomingReaderJob = launch {
                        try {
                            for (frame in incoming) {
                                // Intentionally ignored; ktor answers ping/pong automatically.
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            // Normal client-initiated close.
                        }
                    }
                    incomingReaderJob.invokeOnCompletion { cancel() }

                    // Start buffering live events *before* reading history below, so we never
                    // miss an event that arrives in the gap between the history snapshot and the
                    // point we start forwarding live events. Any overlap between history and the
                    // live buffer is deduplicated below by Kafka offset.
                    val liveEvents = Channel<ConversationEventRecord>(capacity = Channel.UNLIMITED)
                    val liveEventsJob = launch {
                        conversationEventBus.events
                            .filter { it.idEvent.conversationId == conversationId }
                            .collect { liveEvents.send(it) }
                    }
                    liveEventsJob.invokeOnCompletion { liveEvents.close() }

                    val history = conversationEventBus.history(conversationId)
                    for (record in history) {
                        send(Frame.Text(Json.encodeToString(record.idEvent.event)))
                    }
                    val lastHistoryOffset = history.lastOrNull()?.offset ?: -1L

                    for (record in liveEvents) {
                        if (record.offset <= lastHistoryOffset) continue // already sent via history
                        send(Frame.Text(Json.encodeToString(record.idEvent.event)))
                    }
                }
            } catch (t: Throwable) {
                logger.error(t) { "Error in websocket session for conversation $conversationId" }
            } finally {
                MetricRegister.websocketConnections.dec()
                logger.debug { "Closing websocket connection for conversation $conversationId" }
            }
        }
    }
}

