package no.nav.nks_ai.core.admin

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
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
                val navIdent = call.request.queryParameters["navIdent"]
                    ?.let { NavIdent(it) }
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                adminService.getAllConversations(navIdent)
                    .let { call.respond(it) }
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
                val navIdent = call.request.queryParameters["navIdent"]
                    ?.let { NavIdent(it) }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                adminService.deleteAllConversations(navIdent)
                call.respond(HttpStatusCode.NoContent)
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
                val navIdent = call.request.queryParameters["navIdent"]
                    ?.let { NavIdent(it) }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                val conversationId = call.conversationId()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                adminService.deleteConversation(conversationId, navIdent)
                call.respond(HttpStatusCode.NoContent)
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
                val conversationId = call.conversationId()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val conversationSummary = adminService.getConversationSummary(conversationId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(conversationSummary)
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
                val conversationId = call.conversationId()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val messages = adminService.getConversationMessages(conversationId)
                call.respond(messages)
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
                val messageId = call.messageId()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val conversation = adminService.getConversationFromMessageId(messageId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(conversation)
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
                val messageId = call.messageId()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val conversation = adminService.getConversationFromMessageId(messageId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                val conversationSummary = adminService.getConversationSummary(conversation.id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(conversationSummary)
            }
        }
    }
}
