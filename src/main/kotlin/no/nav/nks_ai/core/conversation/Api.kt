package no.nav.nks_ai.core.conversation

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
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

@OptIn(ExperimentalKtorApi::class)
fun Route.conversationRoutes(
    conversationService: ConversationService,
    messageService: MessageService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        get {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.getAllConversations(navIdent))
        }.describe {
            description = "Get all of your conversations"
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<Conversation>>()
                    description = "A list of your conversations"
                }
            }
        }
        post {
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
        }.describe {
            description = "Create a new conversation"
            requestBody {
                schema = jsonSchema<NewConversation>()
                description = "The conversation to be created"
            }
            responses {
                HttpStatusCode.Created {
                    schema = jsonSchema<Conversation>()
                    description = "The conversation that got created"
                }
            }
        }
        get("/{id}") {
            val conversationId = call.conversationId()
                ?: return@get call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.getConversation(conversationId, navIdent))
        }.describe {
            description = "Get a conversation with the given ID"
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "The ID of the conversation"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Conversation>()
                    description = "The conversation requested"
                }
            }
        }
        delete("/{id}") {
            val conversationId = call.conversationId()
                ?: return@delete call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@delete call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(
                HttpStatusCode.NoContent,
                conversationService.deleteConversation(conversationId, navIdent)
            )
        }.describe {
            description = "Delete a conversation with the given ID"
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "The ID of the conversation"
                }
            }
            responses {
                HttpStatusCode.NoContent {
                    description = "The operation was successful"
                }
            }
        }
        put("/{id}") {
            val conversationId = call.conversationId()
                ?: return@put call.respondError(ApplicationError.MissingConversationId())

            val conversation = call.receive<UpdateConversation>()

            val navIdent = call.getNavIdent()
                ?: return@put call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.updateConversation(conversationId, navIdent, conversation))
        }.describe {
            description = "Update a conversation with the given ID"
            requestBody {
                schema = jsonSchema<UpdateConversation>()
                description = "The conversation request to update"
            }
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "The ID of the conversation"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Conversation>()
                    description = "The updated conversation"
                }
            }
        }
        get("/{id}/messages") {
            val conversationId = call.conversationId()
                ?: return@get call.respondError(ApplicationError.MissingConversationId())

            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            call.respondResult(conversationService.getConversationMessages(conversationId, navIdent))
        }.describe {
            description = "Get all messages for a given conversation"
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "The ID of the conversation"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<Message>>()
                    description = "The messages in the conversation"
                }
            }
        }
        post("/{id}/messages") {
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
        post("/{id}/feedback") {
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
        }.describe {
            description = "Create a new feedback for a conversation"
            requestBody {
                schema = jsonSchema<ConversationFeedback>()
                description = "The feedback to be created"
            }
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "ID of the conversation"
                }
            }
            responses {
                HttpStatusCode.Created {
                    schema = jsonSchema<ConversationFeedback>()
                    description = "The feedback that got created"
                }
            }
        }
    }
}
