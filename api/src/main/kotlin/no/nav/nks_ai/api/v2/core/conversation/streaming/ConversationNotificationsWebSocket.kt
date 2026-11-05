package no.nav.nks_ai.api.v2.core.conversation.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.app.getNavIdent
import no.nav.nks_ai.api.core.conversation.ConversationId
import java.time.Instant

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConversationCreatedNotification(val conversationId: ConversationId) {
    @EncodeDefault
    val type: String = "ConversationCreated"
}

fun Route.conversationNotificationsWebSocketV2(
    eventBus: ConversationEventBus,
    hub: ConversationNotificationHub,
    allowedOrigins: Set<String>,
) {
    webSocket("/conversations/notifications/ws") {
        val owner = call.getNavIdent()
        val expiresAt = call.principal<JWTPrincipal>()?.payload?.expiresAt?.toInstant()
        if (owner == null || expiresAt == null || !expiresAt.isAfter(Instant.now()) ||
            call.request.headers[HttpHeaders.Origin] !in allowedOrigins
        ) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Notification connection not permitted"))
            return@webSocket
        }
        val initialReadiness = eventBus.readiness.value
        if (!initialReadiness.isReady) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Notification stream unavailable"))
            return@webSocket
        }
        val subscription = hub.subscribe(owner)
        if (subscription == null) {
            MetricRegister.notificationStreamFailures.labelValues("capacity").inc()
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Notification stream unavailable"))
            return@webSocket
        }
        pingIntervalMillis = 20_000
        timeoutMillis = 20_000
        MetricRegister.notificationWebsocketConnections.inc()
        try {
            coroutineScope {
                launch {
                    delay((expiresAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication expired"))
                    this@coroutineScope.cancel()
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    eventBus.readiness.collect { readiness ->
                        if (!readiness.isReady || readiness.generation != initialReadiness.generation) {
                            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Notification stream unavailable"))
                            this@coroutineScope.cancel()
                        }
                    }
                }
                launch {
                    for (frame in incoming) {
                        if (frame is Frame.Text || frame is Frame.Binary) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Notifications are server push only"))
                            break
                        }
                    }
                }.invokeOnCompletion { cancel() }
                for (conversationId in subscription.events) {
                    if (!expiresAt.isAfter(Instant.now())) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication expired"))
                        break
                    }
                    val sent = withTimeoutOrNull(5_000) {
                        send(Frame.Text(Json.encodeToString(ConversationCreatedNotification(conversationId))))
                        true
                    }
                    if (sent != true) {
                        MetricRegister.notificationStreamFailures.labelValues("send_timeout").inc()
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Notification client cannot keep up"))
                        break
                    }
                    MetricRegister.notificationsSent.inc()
                }
                this@coroutineScope.cancel()
            }
        } catch (_: NotificationStreamUnavailable) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Notification stream unavailable"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            logger.error { "Notification websocket failed (${exception::class.simpleName})" }
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Notification stream failed"))
        } finally {
            subscription.close()
            MetricRegister.notificationWebsocketConnections.dec()
        }
    }
}
