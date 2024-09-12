package no.nav.nks_ai.conversation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.fold
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.ApplicationError
import no.nav.nks_ai.SendMessageService
import no.nav.nks_ai.conversation.ConversationError.ConversationNotFound
import no.nav.nks_ai.fromThrowable
import no.nav.nks_ai.getNavIdent
import no.nav.nks_ai.message.Message
import no.nav.nks_ai.message.MessageRepo
import no.nav.nks_ai.message.NewMessage
import no.nav.nks_ai.now
import no.nav.nks_ai.respondError
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

object Conversations : UUIDTable() {
    val title = varchar("title", 255)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val owner = varchar("owner", 255)
}

class ConversationDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ConversationDAO>(Conversations)

    var title by Conversations.title
    var createdAt by Conversations.createdAt
    var owner by Conversations.owner
}

fun ConversationDAO.Companion.findByIdAndNavIdent(
    conversationId: UUID,
    navIdent: String
): ConversationDAO? =
    find {
        Conversations.id eq conversationId and (Conversations.owner eq navIdent)
    }.firstOrNull()

fun ConversationDAO.toModel() = Conversation(
    id = id.toString(),
    title = title,
    createdAt = createdAt,
    owner = owner,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: LocalDateTime,
    val owner: String,
)

@Serializable
data class NewConversation(
    val title: String,
)

@Serializable
data class UpdateConversation(
    val title: String,
)

class ConversationRepo() {
    suspend fun addConversation(navIdent: String, conversation: NewConversation): Conversation =
        suspendTransaction {
            ConversationDAO.new {
                title = conversation.title
                owner = navIdent
            }.toModel()
        }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String): Unit =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)?.delete()
        }

    suspend fun getConversation(conversationId: UUID, navIdent: String): Conversation? =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)
                ?.toModel()
        }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        suspendTransaction {
            ConversationDAO.find { Conversations.owner eq navIdent }
                .map { it.toModel() }
        }

    suspend fun updateConversation(id: UUID, navIdent: String, conversation: UpdateConversation): Conversation? =
        suspendTransaction {
            ConversationDAO
                .findByIdAndNavIdent(id, navIdent)
                ?.apply {
                    title = conversation.title
                }?.toModel()
        }
}

sealed class ConversationError(
    override val code: HttpStatusCode,
    override val message: String,
    override val description: String
) : ApplicationError(code, message, description) {
    class ConversationNotFound(id: UUID) : ConversationError(
        HttpStatusCode.NotFound,
        "Conversation not found",
        "Conversation with id $id not found"
    )
}

class ConversationService(
    private val conversationRepo: ConversationRepo,
    private val messageRepo: MessageRepo
) {
    suspend fun addConversation(navIdent: String, conversation: NewConversation) =
        conversationRepo.addConversation(navIdent, conversation)

    // TODO metrics
    suspend fun getConversation(
        conversationId: UUID,
        navIdent: String
    ): Either<ConversationNotFound, Conversation> =
        either {
            conversationRepo.getConversation(conversationId, navIdent)
                ?: raise(ConversationNotFound(conversationId))
        }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        conversationRepo.getAllConversations(navIdent)

    suspend fun getConversationMessages(conversationId: UUID, navIdent: String): List<Message> {
        conversationRepo.getConversation(conversationId, navIdent)
            ?: return emptyList()

        return messageRepo.getMessagesByConversation(conversationId)
    }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String): Unit =
        conversationRepo.deleteConversation(conversationId, navIdent)

    suspend fun updateConversation(id: UUID, navIdent: String, conversation: UpdateConversation) =
        conversationRepo.updateConversation(id, navIdent, conversation)
}

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
            val newConversation = call.receiveNullable<NewConversation>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val conversation = conversationService.addConversation(navIdent, newConversation)
            // TODO error?
            call.respond(HttpStatusCode.Created, conversation)
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
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
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
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getConversationMessages(conversationId, navIdent)
                .let { call.respond(it) }
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
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<List<Message>> {
                        description = "The answer from KBS"
                    }
                }
            }
        }) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val newMessage = call.receiveNullable<NewMessage>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val message = sendMessageService.sendMessage(newMessage, conversationId, navIdent)
            if (message == null) {
                return@post call.respond(HttpStatusCode.InternalServerError)
            }

            call.respond(message)
        }
    }
}