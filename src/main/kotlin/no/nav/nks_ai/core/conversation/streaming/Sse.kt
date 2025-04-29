package no.nav.nks_ai.core.conversation.streaming

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.app.sse
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.conversation.conversationId
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.NewMessage

private val logger = KotlinLogging.logger { }

fun Route.conversationSse(
    messageService: MessageService,
    sendMessageService: SendMessageService,
) {
    route("/conversations") {
        sse("/{id}/messages/sse", HttpMethod.Post) {
            val navIdent = call.getNavIdent()
                ?: return@sse call.respondError(ApplicationError.MissingNavIdent())

            val conversationId = call.conversationId()
                ?: return@sse call.respondError(ApplicationError.MissingConversationId())

            // call.receiveNullable was not able to transform from json for some reason ¯\_(ツ)_/¯
            val newMessage = Json.decodeFromString<NewMessage>(call.receiveText())

            logger.debug { "SSE connection established for conversation $conversationId" }

            try {
                MetricRegister.sseConnections.inc()
                async(Dispatchers.IO) {
                    either {
                        val question = messageService.addQuestion(conversationId, navIdent, newMessage.content).bind()
                        send(messageEvent(ConversationEvent.NewMessage(question.id, question)))

                        val messageFlow = sendMessageService.askQuestion(question, conversationId, navIdent)
                            .onLeft { call.respondError(it) }.bind()

                        messageFlow
                            .filter { it !is ConversationEvent.NoOp }
                            .map(::messageEvent)
                            // since the flow is cold, we must collect everything even if the SSE-session is closed,
                            // otherwise the message won't be saved when it completes.
                            .collect(::trySend)
                    }.onLeft { error ->
                        logger.error { "Error in SSE session: $error" }
                    }
                }.await()
            } catch (t: Throwable) {
                logger.error(t) { "Error in SSE session" }
            } finally {
                closeSession()
            }
        }
    }
}

// ignores ClosedWriteChannelException
private suspend fun ServerSSESession.trySend(event: ServerSentEvent) {
    try {
        send(event)
    } catch (ex: ClosedWriteChannelException) {
        logger.debug { "${ex.message}" }
    }
}

private suspend fun ServerSSESession.closeSession() {
    logger.debug { "Closing SSE session" }
    close()
    MetricRegister.sseConnections.dec()
}

private fun messageEvent(event: ConversationEvent) = ServerSentEvent(
    data = Json.encodeToString(event),
)
