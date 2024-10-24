package no.nav.nks_ai.core.conversation

import arrow.core.raise.fold
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.fromThrowable
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.message.NewMessage
import java.util.UUID

fun Route.conversationRoutes(
    conversationService: ConversationService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        get(/* {
            description = "Get all of your conversations"
            response {
                HttpStatusCode.OK to {
                    description = "A list of your conversations"
                    body<List<Conversation>> {
                        description = "A list of your conversations"
                    }
                }
            }
        } */
        ) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getAllConversations(navIdent)
                .let { call.respond(it) }
        }
        post(/*{
            description = "Create a new conversation"
            request {
                body<NewConversation> {
                    description = "The conversation to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The conversation was created"
                    body<Conversation> {
                        description = "The conversation that got created"
                    }
                }
            }
        } */
        ) {
            coroutineScope {
                val newConversation = call.receiveNullable<NewConversation>()
                    ?: return@coroutineScope call.respond(HttpStatusCode.BadRequest)

                val navIdent = call.getNavIdent()
                    ?: return@coroutineScope call.respond(HttpStatusCode.Forbidden)

                val conversation = conversationService.addConversation(navIdent, newConversation)
                val conversationId = UUID.fromString(conversation.id)

                if (newConversation.initialMessage != null) {
                    launch(Dispatchers.IO) {
                        SseChannelHandler.getFlow(conversationId).emitAll(
                            sendMessageService.sendMessageStream(
                                message = newConversation.initialMessage,
                                conversationId = conversationId,
                                navIdent = navIdent
                            )
                        )
                    }
                }

                call.respond(HttpStatusCode.Created, conversation)
            }
        }
        get(
            "/{id}",
            /* {
                       description = "Get a conversation with the given ID"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<Conversation> {
                                   description = "The conversation requested"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            fold(
                block = { conversationService.getConversation(conversationId, navIdent) },
                transform = { call.respond(HttpStatusCode.OK, it) },
                recover = { error: ConversationError -> call.respondError(error) },
                catch = { call.respondError(ApplicationError.fromThrowable(it)) }
            )
        }
        delete(
            "/{id}",
            /* {
                       description = "Delete a conversation with the given ID"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.NoContent to {
                               description = "The operation was successful"
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@delete call.respond(HttpStatusCode.Forbidden)

            conversationService.deleteConversation(conversationId, navIdent)
            call.respond(HttpStatusCode.NoContent)
        }
        put(
            "/{id}",
            /* {
                       description = "Update a conversation with the given ID"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                           body<UpdateConversation> {
                               description = "The conversation request to update"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<Conversation> {
                                   description = "The updated conversation"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val conversation = call.receiveNullable<UpdateConversation>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@put call.respond(HttpStatusCode.Forbidden)

            val updatedConversation = conversationService.updateConversation(conversationId, navIdent, conversation)
            if (updatedConversation == null) {
                return@put call.respond(HttpStatusCode.NotFound)
            }

            call.respond(updatedConversation)
        }
        get(
            "/{id}/messages",
            /* {
                       description = "Get all messages for a given conversation"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<List<Message>> {
                                   description = "The messages in the conversation"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getConversationMessages(conversationId, navIdent)
                ?.let { call.respond(it) }
                ?: return@get call.respond(HttpStatusCode.NotFound)
        }
        post(
            "/{id}/messages",
            /* {
                       description = "Add a new message to the conversation"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                           body<NewMessage> {
                               description = "The new message for the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<List<Message>> {
                                   description = "The answer from KBS"
                               }
                           }
                       }
                   } */
        ) {
            coroutineScope {
                val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                    ?: return@coroutineScope call.respond(HttpStatusCode.BadRequest)

                val newMessage = call.receiveNullable<NewMessage>()
                    ?: return@coroutineScope call.respond(HttpStatusCode.BadRequest)

                val navIdent = call.getNavIdent()
                    ?: return@coroutineScope call.respond(HttpStatusCode.Forbidden)

                launch(Dispatchers.IO) {
                    SseChannelHandler.getFlow(conversationId).emitAll(
                        sendMessageService.sendMessageStream(
                            message = newMessage,
                            conversationId = conversationId,
                            navIdent = navIdent
                        )
                    )
                }

                call.respond(HttpStatusCode.Accepted)
            }
        }
    }
}
