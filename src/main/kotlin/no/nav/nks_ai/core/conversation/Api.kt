package no.nav.nks_ai.core.conversation

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.conversation.streaming.WebsocketFlowHandler
import no.nav.nks_ai.core.message.Feedback
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.NewFeedback
import no.nav.nks_ai.core.message.NewMessage

private val logger = KotlinLogging.logger { }

fun Route.conversationRoutes(
    conversationService: ConversationService,
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
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getAllConversations(navIdent)
                .let { call.respond(it) }
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
                val newConversation = call.receiveNullable<NewConversation>()
                    ?: return@coroutineScope call.respond(HttpStatusCode.BadRequest)

                val navIdent = call.getNavIdent()
                    ?: return@coroutineScope call.respond(HttpStatusCode.Forbidden)

                either {
                    val conversation = conversationService.addConversation(navIdent, newConversation).bind()
                    val conversationId = conversation.id

                    if (newConversation.initialMessage != null) {
                        launch(Dispatchers.IO) {
                            val flow = WebsocketFlowHandler.getFlow(conversationId)
                            sendMessageService.sendMessageStream(
                                message = newConversation.initialMessage,
                                conversationId = conversationId,
                                navIdent = navIdent
                            ).onRight { flow.emitAll(it) }
                                .onLeft { error ->
                                    logger.error { "An error occured when sending message: $error" }
                                }
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
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getConversation(conversationId, navIdent)
                .onLeft { call.respondError(it) }
                .onRight { call.respond(HttpStatusCode.OK, it) }
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
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@delete call.respond(HttpStatusCode.Forbidden)

            conversationService.deleteConversation(conversationId, navIdent)
            call.respond(HttpStatusCode.NoContent)
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
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val conversation = call.receiveNullable<UpdateConversation>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@put call.respond(HttpStatusCode.Forbidden)

            conversationService.updateConversation(conversationId, navIdent, conversation)
                .onLeft { error -> call.respondError(error) }
                .onRight { updatedConversation -> call.respond(updatedConversation) }
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
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getConversationMessages(conversationId, navIdent)
                .onLeft { error -> call.respondError(error) }
                .onRight { call.respond(it) }
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
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val newMessage = call.receiveNullable<NewMessage>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            coroutineScope {
                launch(Dispatchers.IO) {
                    val flow = WebsocketFlowHandler.getFlow(conversationId)

                    sendMessageService.sendMessageStream(
                        message = newMessage,
                        conversationId = conversationId,
                        navIdent = navIdent
                    ).onRight { flow.emitAll(it) }
                        .onLeft { error ->
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
                body<NewFeedback> {
                    description = "The feedback to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The feedback was created"
                    body<Feedback> {
                        description = "The feedback that got created"
                    }
                }
            }
        }) {
            val conversationId = call.conversationId()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val feedback = call.receiveNullable<NewFeedback>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            either {
                conversationService.getConversation(conversationId, navIdent).bind()

                // Feedback won't be saved, just register the metrics.
                when (feedback.liked) {
                    true -> MetricRegister.conversationsLiked.inc()
                    false -> MetricRegister.conversationsDisliked.inc()
                }

                call.respond(HttpStatusCode.Created, Feedback(feedback.liked))
            }.onLeft { call.respondError(it) }
        }
    }
}
