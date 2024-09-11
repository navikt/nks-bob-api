package no.nav.nks_ai.conversation

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.SendMessageService
import no.nav.nks_ai.getNavIdent
import no.nav.nks_ai.message.Message
import no.nav.nks_ai.message.MessageRepo
import no.nav.nks_ai.message.MessageService
import no.nav.nks_ai.message.NewMessage
import no.nav.nks_ai.now
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
    val createdAt = datetime("created_at")
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
    val navIdent: String
)

@Serializable
data class UpdateConversation(
    val title: String,
)

class ConversationRepo() {
    suspend fun addConversation(conversation: NewConversation): Conversation =
        suspendTransaction {
            ConversationDAO.new {
                title = conversation.title
                owner = conversation.navIdent
                createdAt = LocalDateTime.now()
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

class ConversationService(
    private val conversationRepo: ConversationRepo,
    private val messageRepo: MessageRepo
) {
    suspend fun addConversation(conversation: NewConversation) =
        conversationRepo.addConversation(conversation)

    // TODO metrics
    suspend fun getConversation(conversationId: UUID, navIdent: String): Conversation? =
        conversationRepo.getConversation(conversationId, navIdent)

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

private val logger = KotlinLogging.logger {}

fun Route.conversationRoutes(
    conversationService: ConversationService,
    messageService: MessageService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        get {
            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getAllConversations(navIdent)
                .let { call.respond(it) }
        }
        post {
            val newConversation = call.receiveNullable<NewConversation>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val conversation = conversationService.addConversation(newConversation)
            // TODO error?
            call.respond(conversation)
        }
        get("/{id}") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            val conversation = conversationService.getConversation(conversationId, navIdent)
            if (conversation == null) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.respond(conversation)
        }
        delete("/{id}") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@delete call.respond(HttpStatusCode.Forbidden)

            conversationService.deleteConversation(conversationId, navIdent)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/{id}") {
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
        get("/{id}/messages") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getConversationMessages(conversationId, navIdent)
                .let { call.respond(it) }
        }
        post("/{id}/messages") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val newMessage = call.receiveNullable<NewMessage>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val token = call.request.headers.get(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val message = sendMessageService.sendMessage(newMessage, conversationId, navIdent, token)
            if (message == null) {
                return@post call.respond(HttpStatusCode.InternalServerError)
            }

            call.respond(message)
        }
    }
}