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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.app.getNavIdent
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationService
import no.nav.nks_ai.api.core.conversation.conversationId
import no.nav.nks_ai.api.core.user.NavIdent
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger { }
// History is capped at 500 events; the extra space covers live events arriving during replay.
private const val LIVE_EVENTS_BUFFER_CAPACITY = 1024

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
    canAccessConversation: suspend (ConversationId, NavIdent) -> Boolean,
    conversationEventBus: ConversationEventBus,
    activeConversationService: ActiveConversationService,
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
            val hasAccess = canAccessConversation(conversationId, navIdent)
            if (!hasAccess) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Conversation not found"))
                return@webSocket
            }

            val initialReadiness = conversationEventBus.readiness.value
            if (!initialReadiness.isReady) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Conversation stream unavailable"))
                return@webSocket
            }

            // Registers this connection so it shows up as an active/live conversation elsewhere
            // (e.g. admin listing, statistics). connect() re-validates ownership against the
            // conversations table itself, so this also guards against a conversation being
            // deleted between the check above and this call.
            val connectionId = activeConversationService.connect(conversationId, navIdent).getOrNull()
            if (connectionId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Conversation not found"))
                return@webSocket
            }

            logger.debug { "Websocket connection established for conversation $conversationId" }
            MetricRegister.websocketConnections.inc()

            try {
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        conversationEventBus.readiness.collect { readiness ->
                            if (!readiness.isReady || readiness.generation != initialReadiness.generation) {
                                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Conversation stream unavailable"))
                                this@coroutineScope.cancel()
                            }
                        }
                    }

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

                    // Keep the connection's lease alive for as long as the socket is open, so it
                    // isn't swept up by the expired-connections cleanup job while still in use.
                    val renewJob = launch {
                        while (true) {
                            delay(ActiveConversationService.RENEW_INTERVAL)
                            val renewed = activeConversationService.renew(connectionId).isRight()
                            if (!renewed) {
                                logger.warn { "Active connection $connectionId expired; closing websocket" }
                                close(CloseReason(CloseReason.Codes.NORMAL, "Connection expired"))
                                break
                            }
                        }
                    }
                    renewJob.invokeOnCompletion { cancel() }

                    // Start buffering live events *before* reading history below, so we never
                    // miss an event that arrives in the gap between the history snapshot and the
                    // point we start forwarding live events. Any overlap between history and the
                    // live buffer is deduplicated below by Kafka offset.
                    val liveEvents = Channel<ConversationEventRecord>(capacity = LIVE_EVENTS_BUFFER_CAPACITY)
                    val overflowed = AtomicBoolean(false)
                    val liveEventsJob = launch {
                        conversationEventBus.events
                            .filter { it.idEvent.conversationId == conversationId }
                            .collect {
                                if (liveEvents.trySend(it).isFailure) {
                                    overflowed.set(true)
                                    liveEvents.close()
                                    close(
                                        CloseReason(
                                            CloseReason.Codes.TRY_AGAIN_LATER,
                                            "Websocket client cannot keep up",
                                        )
                                    )
                                    currentCoroutineContext().cancel()
                                }
                            }
                    }
                    liveEventsJob.invokeOnCompletion { liveEvents.close() }

                    val history = conversationEventBus.history(conversationId)
                    for (record in history) {
                        if (overflowed.get()) break
                        send(Frame.Text(Json.encodeToString(record.idEvent.event)))
                    }
                    val lastHistoryOffset = history.lastOrNull()?.offset ?: -1L

                    for (record in liveEvents) {
                        if (overflowed.get()) break
                        if (record.offset <= lastHistoryOffset) continue // already sent via history
                        send(Frame.Text(Json.encodeToString(record.idEvent.event)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.error {
                    "Error in websocket session for conversation $conversationId (${t::class.simpleName})"
                }
            } finally {
                activeConversationService.disconnect(connectionId)
                MetricRegister.websocketConnections.dec()
                logger.debug { "Closing websocket connection for conversation $conversationId" }
            }
        }
    }
}
