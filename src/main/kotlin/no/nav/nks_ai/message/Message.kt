package no.nav.nks_ai.message

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.conversation.ConversationDAO
import no.nav.nks_ai.conversation.Conversations
import no.nav.nks_ai.feedback.Feedback
import no.nav.nks_ai.feedback.FeedbackDAO
import no.nav.nks_ai.feedback.FeedbackRepo
import no.nav.nks_ai.feedback.Feedbacks
import no.nav.nks_ai.feedback.NewFeedback
import no.nav.nks_ai.feedback.toModel
import no.nav.nks_ai.now
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

object Messages : UUIDTable() {
    val content = text("content", eagerLoading = true)
    val conversation = reference("conversation", Conversations)
    val feedback = reference(
        "feedback",
        Feedbacks,
        onDelete = ReferenceOption.SET_NULL,
        onUpdate = ReferenceOption.CASCADE
    ).nullable()
    val createdAt = datetime("created_at")
    // messageType Question | Answer
    // createdBy Bob | NavIdent(String)
}

class MessageDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MessageDAO>(Messages)

    var content by Messages.content
    var conversation by ConversationDAO.Companion referencedOn Messages.conversation
    var feedback by FeedbackDAO.Companion optionalReferencedOn Messages.feedback
    var createdAt by Messages.createdAt
}

fun MessageDAO.toModel() = Message(
    id = id.toString(),
    content = content,
    createdAt = createdAt,
    feedback = feedback?.toModel()
)

@Serializable
data class Message(
    val id: String,
    val content: String,
    val createdAt: LocalDateTime,
    val feedback: Feedback?,
)

@Serializable
data class NewMessage(
    val content: String,
)

class MessageRepo() {
    suspend fun addMessage(newMessage: NewMessage, conversationId: UUID): Message? =
        suspendTransaction {
            val conversation = ConversationDAO.findById(conversationId)
                ?: return@suspendTransaction null // TODO error

            MessageDAO.new {
                this.content = newMessage.content
                this.conversation = conversation
                createdAt = LocalDateTime.now()
            }.toModel()
        }

    suspend fun getMessage(id: UUID): Message? =
        suspendTransaction {
            MessageDAO.findById(id)
                ?.load(MessageDAO::feedback)
                ?.toModel()
        }

    suspend fun getAllMessages(): List<Message> =
        suspendTransaction {
            MessageDAO.all()
                .with(MessageDAO::feedback)
                .map { it.toModel() }
        }

    suspend fun getMessagesByConversation(conversationId: UUID): List<Message> =
        suspendTransaction {
            MessageDAO.find { Messages.conversation eq conversationId }
                .sortedBy { Messages.createdAt }
                .with(MessageDAO::feedback)
                .map { it.toModel() }
        }

    suspend fun addFeedback(messageId: UUID, feedback: FeedbackDAO): Message? =
        suspendTransaction {
            MessageDAO.findByIdAndUpdate(messageId) { it.feedback = feedback }
                ?.load(MessageDAO::feedback)
                ?.toModel()
        }
}

class MessageService(
    private val messageRepo: MessageRepo,
    private val feedbackRepo: FeedbackRepo
) {
    suspend fun addMessage(newMessage: NewMessage, conversationId: UUID): Message? =
        messageRepo.addMessage(newMessage, conversationId)

    suspend fun getMessage(messageId: UUID): Message? =
        messageRepo.getMessage(messageId)

    suspend fun addFeedbackToMessage(messageId: UUID, newFeedback: NewFeedback): Message? {
        val message = messageRepo.getMessage(messageId) ?: return null

        if (message.feedback != null) {
            // don't add dupliate feedbacks
            if (message.feedback.liked == newFeedback.liked) {
                return message
            }

            // remove existing
            feedbackRepo.removeFeedback(UUID.fromString(message.feedback.id))
        }

        val feedback = feedbackRepo.addFeedback(newFeedback)
        return messageRepo.addFeedback(messageId, feedback)
    }
}

fun Route.messageRoutes(messageService: MessageService) {
    route("/messages") {
        get("/{id}") {
            val messageId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val message = messageService.getMessage(messageId)
            if (message == null) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.respond(message)
        }
        post("/{id}/feedback") {
            val messageId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val feedback = call.receiveNullable<NewFeedback>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val message = messageService.addFeedbackToMessage(messageId, feedback)
            if (message == null) {
                return@post call.respond(HttpStatusCode.NotFound)
            }

            call.respond(message)
        }
    }
}
