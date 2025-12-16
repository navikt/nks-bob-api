package no.nav.nks_ai.core.admin

import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.respondEither
import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationSummary
import no.nav.nks_ai.core.conversation.conversationId
import no.nav.nks_ai.core.message.messageId
import no.nav.nks_ai.core.user.NavIdent

fun Route.adminRoutes(adminService: AdminService) {
    route("/admin") {
        route("/conversations") {
            get({
                description = "Get all conversations for a given user"
                request {
                    queryParameter<String>("navIdent") {
                        description = "navIdent for the given user"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "A list the users conversations"
                        body<List<Conversation>> {
                            description = "A list of the users conversations"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val navIdent = call.request.queryParameters["navIdent"]
                        ?.let { NavIdent(it) }
                        ?: raise(missingNavIdent())

                    adminService.getAllConversations(navIdent)
                }
            }
            delete({
                description = "Delete all conversations for a given user"
                request {
                    queryParameter<String>("navIdent") {
                        description = "navIdent for the given user"
                    }
                }
                response {
                    HttpStatusCode.NoContent to {
                        description = "The operation was successful"
                    }
                }
            }) {
                call.respondEither(HttpStatusCode.NoContent) {
                    val navIdent = call.request.queryParameters["navIdent"]
                        ?.let { NavIdent(it) }
                        ?: raise(missingNavIdent())

                    adminService.deleteAllConversations(navIdent)
                }
            }
            get("/{id}", {
                description = "Get conversation by id"
                request {
                    pathParameter<String>("id") {
                        description = "The ID of the conversation"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The requested conversation"
                        body<Conversation> {
                            description = "The requested conversation"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())

                    adminService.getConversation(conversationId)
                }
            }
            delete("/{id}", {
                description = "Delete a conversation with the given ID for the given user"
                request {
                    pathParameter<String>("id") {
                        description = "The ID of the conversation"
                    }
                    queryParameter<String>("navIdent") {
                        description = "navIdent for the given user"
                    }
                }
                response {
                    HttpStatusCode.NoContent to {
                        description = "The operation was successful"
                    }
                }
            }) {
                call.respondEither(HttpStatusCode.NoContent) {
                    val navIdent = call.request.queryParameters["navIdent"]
                        ?.let { NavIdent(it) }
                        ?: raise(missingNavIdent())

                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())

                    adminService.deleteConversation(conversationId, navIdent)
                }
            }
            get("/{id}/summary", {
                description = "Get conversation summary for the given conversation ID"
                request {
                    pathParameter<String>("id") {
                        description = "The ID of the conversation"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<ConversationSummary>() {
                            description = "Conversation summary"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())

                    adminService.getConversationSummary(conversationId)
                }
            }
            get("/{id}/messages", {
                description = "Get all messages for the given conversation ID"
                request {
                    pathParameter<String>("id") {
                        description = "The ID of the conversation"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<ConversationSummary>() {
                            description = "Conversation messages"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())

                    adminService.getConversationMessages(conversationId)
                }
            }
        }
        route("/messages") {
            get("/{id}/conversation", {
                description = "Get the conversation for the given message ID"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the message"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<Conversation> {
                            description = "The conversation which the message belongs to"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val messageId = call.messageId()
                        ?: raise(ApplicationError.MissingMessageId())

                    adminService.getConversationFromMessageId(messageId)
                }
            }
            get("/{id}/conversation/summary", {
                description = "Get conversation summary for the given message ID"
                request {
                    pathParameter<String>("id") {
                        description = "The ID of the message"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<ConversationSummary> {
                            description = "Conversation summary"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val messageId = call.messageId()
                        ?: raise(ApplicationError.MissingMessageId())

                    val conversation = adminService.getConversationFromMessageId(messageId).bind()
                    adminService.getConversationSummary(conversation.id)
                }
            }
        }
    }
}

private fun missingNavIdent() = ApplicationError.BadRequest(
    "This request does not contain the required query parameter \"navIdent\""
)