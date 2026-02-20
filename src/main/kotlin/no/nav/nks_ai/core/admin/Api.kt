package no.nav.nks_ai.core.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.navIdent
import no.nav.nks_ai.app.respondEither
import no.nav.nks_ai.app.teamLogger
import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationSummary
import no.nav.nks_ai.core.conversation.conversationId
import no.nav.nks_ai.core.message.messageId

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

fun Route.adminRoutes(adminService: AdminService) {
    route("/admin") {
        route("/conversations") {
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
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}" }

                    adminService.getConversation(conversationId)
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
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}/summary" }

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
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}/messages" }

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
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=message/${messageId.value}/conversation" }

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
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=message/${messageId.value}/conversation/summary" }

                    val conversation = adminService.getConversationFromMessageId(messageId).bind()
                    adminService.getConversationSummary(conversation.id)
                }
            }
        }
    }
}