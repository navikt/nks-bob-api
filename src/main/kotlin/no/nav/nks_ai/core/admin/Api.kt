package no.nav.nks_ai.core.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.navIdent
import no.nav.nks_ai.app.respondEither
import no.nav.nks_ai.app.teamLogger
import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationSummary
import no.nav.nks_ai.core.conversation.conversationId
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.messageId

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

@OptIn(ExperimentalKtorApi::class)
fun Route.adminRoutes(adminService: AdminService) {
    route("/admin") {
        route("/conversations") {
            get("/{id}") {
                call.respondEither {
                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}" }

                    adminService.getConversation(conversationId)
                }
            }.describe {
                description = "Get conversation by id"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "The ID of the conversation"
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<Conversation>()
                        description = "The requested conversation"
                    }
                }
            }
            get("/{id}/summary") {
                call.respondEither {
                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}/summary" }

                    adminService.getConversationSummary(conversationId)
                }
            }.describe {
                description = "Get conversation summary for the given conversation ID"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "The ID of the conversation"
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<ConversationSummary>()
                        description = "Conversation summary"
                    }
                }
            }
            get("/{id}/messages") {
                call.respondEither {
                    val conversationId = call.conversationId()
                        ?: raise(ApplicationError.MissingConversationId())
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}/messages" }

                    adminService.getConversationMessages(conversationId)
                }
            }.describe {
                description = "Get all messages for the given conversation ID"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "The ID of the conversation"
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<List<Message>>()
                        description = "Conversation messages"
                    }
                }
            }
        }
        route("/messages") {
            get("/{id}/conversation") {
                call.respondEither {
                    val messageId = call.messageId()
                        ?: raise(ApplicationError.MissingMessageId())
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=message/${messageId.value}/conversation" }

                    adminService.getConversationFromMessageId(messageId)
                }
            }.describe {
                description = "Get the conversation for the given message ID"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the message"
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        jsonSchema<Conversation>()
                        description = "The conversation which the message belongs to"
                    }
                }
            }
            get("/{id}/conversation/summary") {
                call.respondEither {
                    val messageId = call.messageId()
                        ?: raise(ApplicationError.MissingMessageId())
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=message/${messageId.value}/conversation/summary" }

                    val conversation = adminService.getConversationFromMessageId(messageId).bind()
                    adminService.getConversationSummary(conversation.id)
                }
            }.describe {
                description = "Get conversation summary for the given message ID"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "The ID of the message"
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<ConversationSummary>()
                        description = "Conversation summary"
                    }
                }
            }
        }
    }
}