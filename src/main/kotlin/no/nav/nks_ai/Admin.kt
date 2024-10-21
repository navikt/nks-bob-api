package no.nav.nks_ai

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.nks_ai.conversation.Conversation
import no.nav.nks_ai.conversation.ConversationRepo
import java.util.UUID

class AdminService(
    private val conversationRepo: ConversationRepo
) {
    suspend fun deleteAllConversations(navIdent: String) {
        conversationRepo.deleteAllConversations(navIdent)
    }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String) {
        conversationRepo.deleteConversation(conversationId, navIdent)
    }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        conversationRepo.getAllConversations(navIdent)
}

fun Route.adminRoutes(adminService: AdminService) {
    route("/admin") {
        route("/conversations") {
            get(/* {
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
            } */
            ) {
                val navIdent = call.request.queryParameters["navIdent"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                adminService.getAllConversations(navIdent)
                    .let { call.respond(it) }
            }
            delete(/* {
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
            } */
            ) {
                val navIdent = call.request.queryParameters["navIdent"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                adminService.deleteAllConversations(navIdent)
                call.respond(HttpStatusCode.NoContent)
            }
            delete(
                "/{id}",
                /* {
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
                           } */
            ) {
                val navIdent = call.request.queryParameters["navIdent"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                adminService.deleteConversation(conversationId, navIdent)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
