package no.nav.nks_ai.api.core.conversation

import arrow.core.right
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
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
import kotlinx.coroutines.launch
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.app.navIdent
import no.nav.nks_ai.api.app.respondEither
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.v2.core.SendMessageService

@OptIn(ExperimentalKtorApi::class)
fun Route.conversationRoutes(
    conversationService: ConversationService,
    messageService: MessageService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        get {
            call.respondEither {
                val navIdent = call.navIdent().bind()
                conversationService.getAllConversations(navIdent)
            }
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
                call.respondEither(HttpStatusCode.Created) {
                    val navIdent = call.navIdent().bind()
                    val newConversation = call.receive<NewConversation>()
                    val conversation = conversationService.addConversation(navIdent, newConversation).bind()

                    if (newConversation.initialMessage != null) {
                        val question =
                            messageService.addQuestion(
                                conversation.id,
                                navIdent,
                                newConversation.initialMessage.content
                            ).bind()

                        val flow = sendMessageService.askQuestion(
                            question = question,
                            conversationId = conversation.id,
                            navIdent = navIdent
                        ).bind()

                        launch(Dispatchers.IO) {
                            flow.collect {}
                        }
                    }

                    conversation.right()
                }
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
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val conversationId = call.conversationId().bind()

                conversationService.getConversation(conversationId, navIdent)
            }
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
            call.respondEither(HttpStatusCode.NoContent) {
                val navIdent = call.navIdent().bind()
                val conversationId = call.conversationId().bind()

                conversationService.deleteConversation(conversationId, navIdent)
            }
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
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val conversationId = call.conversationId().bind()
                val conversation = call.receive<UpdateConversation>()

                conversationService.updateConversation(conversationId, navIdent, conversation)
            }
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
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val conversationId = call.conversationId().bind()

                conversationService.getConversationMessages(conversationId, navIdent)
            }
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
            coroutineScope {
                call.respondEither<Unit>(HttpStatusCode.Accepted) {
                    val navIdent = call.navIdent().bind()
                    val conversationId = call.conversationId().bind()
                    val newMessage = call.receive<NewMessage>()

                    val question = messageService.addQuestion(conversationId, navIdent, newMessage.content).bind()
                    val flow = sendMessageService.askQuestion(
                        question = question,
                        conversationId = conversationId,
                        navIdent = navIdent
                    ).bind()

                    launch(Dispatchers.IO) {
                        flow.collect {}
                    }
                    Unit.right()
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
        post("/{id}/feedback") {
            call.respondEither(HttpStatusCode.Created) {
                val navIdent = call.navIdent().bind()
                val conversationId = call.conversationId().bind()
                val feedback = call.receive<ConversationFeedback>()

                conversationService.getConversation(conversationId, navIdent).bind()

                // Feedback won't be saved, just register the metrics.
                when (feedback.liked) {
                    true -> MetricRegister.conversationsLiked.inc()
                    false -> MetricRegister.conversationsDisliked.inc()
                }

                ConversationFeedback(feedback.liked).right()
            }
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
