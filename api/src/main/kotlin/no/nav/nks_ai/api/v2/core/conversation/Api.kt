package no.nav.nks_ai.api.v2.core.conversation

import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.flow.toList
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.navIdent
import no.nav.nks_ai.api.app.respondEither
import no.nav.nks_ai.api.core.conversation.conversationId
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.v2.core.SendMessageService
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEvent

private val logger = KotlinLogging.logger { }

@OptIn(ExperimentalKtorApi::class)
fun Route.conversationRoutesV2(
    messageService: MessageService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        post("/{id}/messages") {
            call.respondEither {
                val conversationId = call.conversationId()
                    ?: raise(ApplicationError.MissingConversationId())

                val newMessage = call.receive<NewMessage>()
                val navIdent = call.navIdent().bind()

                val list = mutableListOf<ConversationEvent>()
                val question = messageService.addQuestion(conversationId, navIdent, newMessage.content).bind()
                    .also { list.add(ConversationEvent.NewMessage(it.id, it)) }

                sendMessageService.askQuestion(
                    question = question,
                    conversationId = conversationId,
                    navIdent = navIdent
                ).bind().toList(list).right()


            }.onLeft { error ->
                logger.error { "An error occured when sending message: $error" }
            }
        }
    }.describe {
        description = "Add a new message to the conversation"
        requestBody {
            schema = jsonSchema<NewMessage>()
            description = "The new message for the conversation"
        }
        parameters {
            path("id") {
                schema = jsonSchema<String>()
                description = "The ID of the conversation"
            }
        }
        responses {
            HttpStatusCode.Accepted {
                description = "The operation will be processed"
            }
        }
    }
}
