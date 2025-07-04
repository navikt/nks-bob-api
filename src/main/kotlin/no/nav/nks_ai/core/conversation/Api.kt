package no.nav.nks_ai.core.conversation

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.app.respondResult
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.conversation.streaming.WebsocketFlowHandler
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.NewMessage

private val logger = KotlinLogging.logger { }

fun Route.conversationRoutes(
    conversationService: ConversationService,
    messageService: MessageService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        get({
            description = "Get all of your conversations"
            response {
                HttpStatusCode.OK to {
                    description = "A list of your conversations"
                    body<List<Conversation>> {
                        description = "A list of your conversations"
                    }
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.getAllConversations(navIdent))
        }
        post({
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
        }) {
            coroutineScope {
                val newConversation = call.receive<NewConversation>()

                val navIdent = call.getNavIdent()
                    ?: return@coroutineScope call.respondError(ApplicationError.MissingNavIdent())

                either {
                    val conversation = conversationService.addConversation(navIdent, newConversation).bind()
                    val conversationId = conversation.id

                    if (newConversation.initialMessage != null) {
                        launch(Dispatchers.IO) {
                            val flow = WebsocketFlowHandler.getFlow(conversationId)
                            val question =
                                messageService.addQuestion(
                                    conversationId,
                                    navIdent,
                                    newConversation.initialMessage.content
                                ).bind()

                            sendMessageService.askQuestion(
                                question = question,
                                conversationId = conversationId,
                                navIdent = navIdent
                            ).onRight { flow.emitAll(it) }
                        }
                    }

                    call.respond(HttpStatusCode.Created, conversation)
                }.onLeft { call.respondError(it) }
            }
        }
        get("/{id}", {
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
        }) {
            val conversationId = call.conversationId()
                ?: return@get call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.getConversation(conversationId, navIdent))
        }
        delete("/{id}", {
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
        }) {
            val conversationId = call.conversationId()
                ?: return@delete call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@delete call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(
                HttpStatusCode.NoContent,
                conversationService.deleteConversation(conversationId, navIdent)
            )
        }
        put("/{id}", {
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
        }) {
            val conversationId = call.conversationId()
                ?: return@put call.respondError(ApplicationError.MissingConversationId())

            val conversation = call.receive<UpdateConversation>()

            val navIdent = call.getNavIdent()
                ?: return@put call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.updateConversation(conversationId, navIdent, conversation))
        }
        get("/{id}/messages", {
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
        }) {
            val conversationId = call.conversationId()
                ?: return@get call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.getConversationMessages(conversationId, navIdent))
        }
        post("/{id}/messages", {
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
                HttpStatusCode.Accepted to {
                    description = "The operation will be processed"
                }
            }
        }) {
            val conversationId = call.conversationId()
                ?: return@post call.respondError(ApplicationError.MissingConversationId())

            val newMessage = call.receive<NewMessage>()

            val navIdent = call.getNavIdent()
                ?: return@post call.respondError(ApplicationError.MissingNavIdent())

            coroutineScope {
                launch(Dispatchers.IO) {
                    either {
                        val flow = WebsocketFlowHandler.getFlow(conversationId)
                        val question = messageService.addQuestion(conversationId, navIdent, newMessage.content).bind()

                        sendMessageService.askQuestion(
                            question = question,
                            conversationId = conversationId,
                            navIdent = navIdent
                        ).onRight { flow.emitAll(it) }

                    }.onLeft { error ->
                        logger.error { "An error occured when sending message: $error" }
                    }
                }

                call.respond(HttpStatusCode.Accepted)
            }
        }
        post("/{id}/feedback", {
            description = "Create a new feedback for a conversation"
            request {
                pathParameter<String>("id") {
                    description = "ID of the conversation"
                }
                body<ConversationFeedback> {
                    description = "The feedback to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The feedback was created"
                    body<ConversationFeedback> {
                        description = "The feedback that got created"
                    }
                }
            }
        }) {
            val conversationId = call.conversationId()
                ?: return@post call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@post call.respondError(ApplicationError.MissingNavIdent())

            val feedback = call.receive<ConversationFeedback>()

            either {
                conversationService.getConversation(conversationId, navIdent).bind()

                // Feedback won't be saved, just register the metrics.
                when (feedback.liked) {
                    true -> MetricRegister.conversationsLiked.inc()
                    false -> MetricRegister.conversationsDisliked.inc()
                }

                call.respond(HttpStatusCode.Created, ConversationFeedback(feedback.liked))
            }.onLeft { call.respondError(it) }
        }
    }
}
