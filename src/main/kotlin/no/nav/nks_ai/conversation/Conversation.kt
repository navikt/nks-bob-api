package no.nav.nks_ai.conversation

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
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

object Conversations : UUIDTable() {
    val title = varchar("title", 255)
    val createdAt = datetime("created_at")
//    val createdBy = varchar("created_by", 255) // TODO ref med NAV-ident
}

class ConversationDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ConversationDAO>(Conversations)

    var title by Conversations.title
    var createdAt by Conversations.createdAt
//    var createdBy by Conversations.createdBy
}

fun ConversationDAO.toModel() = Conversation(
    id = id.toString(),
    title = title,
    createdAt = createdAt
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: LocalDateTime,
)

@Serializable
data class NewConversation(val title: String)

class ConversationRepo() {
    suspend fun addConversation(conversation: NewConversation): Conversation =
        suspendTransaction {
            ConversationDAO.new {
                title = conversation.title
                createdAt = LocalDateTime.now()
            }.toModel()
        }

    suspend fun deleteConversation(conversationId: UUID): Unit =
        suspendTransaction {
            ConversationDAO.findById(conversationId)?.delete()
        }

    // TODO createdBy
    suspend fun getConversation(conversationId: UUID): Conversation? =
        suspendTransaction {
            ConversationDAO.findById(conversationId)
                ?.toModel()
        }

    // TODO createdBy
    suspend fun getAllConversations(): List<Conversation> =
        suspendTransaction {
            ConversationDAO.all()
                .map { it.toModel() }
        }

    suspend fun updateConversation(id: UUID, conversation: NewConversation): Conversation? =
        suspendTransaction {
            ConversationDAO.findByIdAndUpdate(id) {
                it.title = conversation.title
            }?.toModel()
        }
}

class ConversationService(val conversationRepo: ConversationRepo, val messageRepo: MessageRepo) {
    suspend fun addConversation(conversation: NewConversation) =
        conversationRepo.addConversation(conversation)

    // TODO metrics
    suspend fun getConversation(conversationId: UUID): Conversation? =
        conversationRepo.getConversation(conversationId)

    suspend fun getAllConversations(): List<Conversation> =
        conversationRepo.getAllConversations()

    suspend fun getConversationMessages(conversationId: UUID): List<Message> =
        messageRepo.getMessagesByConversation(conversationId)

    suspend fun deleteConversation(conversationId: UUID): Unit =
        conversationRepo.deleteConversation(conversationId)

    suspend fun updateConversation(id: UUID, conversation: NewConversation) =
        conversationRepo.updateConversation(id, conversation)
}

fun Route.conversationRoutes(
    conversationService: ConversationService,
    messageService: MessageService
) {
//        authenticate {
    route("/conversations") {
        get {
            conversationService.getAllConversations()
                .let { call.respond(it) }
        }
        post {
            val newConversation = call.receiveNullable<NewConversation>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val conversation = conversationService.addConversation(newConversation)
            // TODO nullable?
            call.respond(conversation)
        }
        get("/{id}") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val conversation = conversationService.getConversation(conversationId)
            if (conversation == null) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.respond(conversation)
        }
        delete("/{id}") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            conversationService.deleteConversation(conversationId)
            // TODO response
        }
        put("/{id}") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val conversation = call.receiveNullable<NewConversation>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val updatedConversation = conversationService.updateConversation(conversationId, conversation)
            if (updatedConversation == null) {
                return@put call.respond(HttpStatusCode.NotFound)
            }

            call.respond(updatedConversation)
        }
        get("/{id}/messages") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            conversationService.getConversationMessages(conversationId)
                .let { call.respond(it) }
        }
        post("/{id}/messages") {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val newMessage = call.receiveNullable<NewMessage>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val message = messageService.addMessage(newMessage, conversationId)
            if (message == null) {
                return@post call.respond(HttpStatusCode.NotFound)
            }

            call.respond(message)
        }
    }
//    }
}