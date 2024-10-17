package no.nav.nks_ai.message

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import no.nav.nks_ai.citation.Citation
import no.nav.nks_ai.citation.CitationDAO
import no.nav.nks_ai.citation.CitationRepo
import no.nav.nks_ai.citation.Citations
import no.nav.nks_ai.citation.NewCitation
import no.nav.nks_ai.citation.toModel
import no.nav.nks_ai.conversation.ConversationDAO
import no.nav.nks_ai.conversation.Conversations
import no.nav.nks_ai.feedback.Feedback
import no.nav.nks_ai.feedback.NewFeedback
import no.nav.nks_ai.feedback.fromNewFeedback
import no.nav.nks_ai.now
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

val jsonConfig = Json.Default

object Messages : UUIDTable() {
    val content = text("content", eagerLoading = true)
    val conversation = reference("conversation", Conversations)
    val feedback = jsonb<Feedback>("feedback", jsonConfig).nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val messageType = enumeration<MessageType>("message_type")
    val messageRole = enumeration<MessageRole>("message_role")
    val createdBy = varchar("created_by", 255)
    val context = jsonb<List<Context>>("context", jsonConfig).clientDefault { emptyList() }
}

@Serializable
enum class MessageType {
    @SerialName("question")
    Question,

    @SerialName("answer")
    Answer,
}

@Serializable
enum class MessageRole {
    @SerialName("human")
    Human,

    @SerialName("ai")
    AI,
}

@Serializable
data class Context(
    val content: String,
    val metadata: JsonObject,
)

class MessageDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MessageDAO>(Messages)

    var content by Messages.content
    var conversation by ConversationDAO referencedOn Messages.conversation
    var feedback by Messages.feedback
    val citations by CitationDAO referrersOn Citations.message
    var createdAt by Messages.createdAt
    var messageType by Messages.messageType
    var messageRole by Messages.messageRole
    var createdBy by Messages.createdBy
    var context by Messages.context
}

fun MessageDAO.toModel() = Message(
    id = id.toString(),
    content = content,
    createdAt = createdAt,
    feedback = feedback,
    messageType = messageType,
    messageRole = messageRole,
    createdBy = createdBy,
    citations = citations.map { it.toModel() },
    context = context
)

@Serializable
data class Message(
    val id: String,
    val content: String,
    val createdAt: LocalDateTime,
    val feedback: Feedback?,
    val messageType: MessageType,
    val messageRole: MessageRole,
    val createdBy: String,
    val citations: List<Citation>,
    val context: List<Context>,
)

@Serializable
data class NewMessage(
    val content: String,
)

class MessageRepo() {
    suspend fun addMessage(
        conversationId: UUID,
        messageContent: String,
        messageType: MessageType,
        messageRole: MessageRole,
        createdBy: String,
        context: List<Context>,
    ): Message? =
        suspendTransaction {
            val conversation = ConversationDAO.findById(conversationId)
                ?: return@suspendTransaction null // TODO error

            MessageDAO.new {
                this.content = messageContent
                this.conversation = conversation
                this.messageType = messageType
                this.messageRole = messageRole
                this.createdBy = createdBy
                this.context = context
            }.toModel()
        }

    suspend fun updateMessage(
        messageId: UUID,
        messageContent: String,
        messageType: MessageType,
        messageRole: MessageRole,
        createdBy: String,
        context: List<Context>,
    ): Message? =
        suspendTransaction {
            MessageDAO.findByIdAndUpdate(messageId) {
                it.content = messageContent
                it.messageType = messageType
                it.messageRole = messageRole
                it.createdBy = createdBy
                it.context = context
            }?.toModel()
        }

    suspend fun getMessage(id: UUID): Message? =
        suspendTransaction {
            MessageDAO.findById(id)
                ?.toModel()
        }

    suspend fun getMessagesByConversation(conversationId: UUID): List<Message> =
        suspendTransaction {
            MessageDAO.find { Messages.conversation eq conversationId }
                .sortedBy { Messages.createdAt }
                .map { it.toModel() }
        }

    suspend fun addFeedback(messageId: UUID, newFeedback: NewFeedback): Message? =
        suspendTransaction {
            MessageDAO.findByIdAndUpdate(messageId) {
                it.feedback = Feedback.fromNewFeedback(newFeedback)
            }?.toModel()
        }
}

class MessageService(
    private val messageRepo: MessageRepo,
    private val citationRepo: CitationRepo
) {
    suspend fun addQuestion(
        conversationId: UUID,
        navIdent: String,
        messageContent: String,
    ) = messageRepo.addMessage(
        conversationId = conversationId,
        messageContent = messageContent,
        createdBy = navIdent,
        messageType = MessageType.Question,
        messageRole = MessageRole.Human,
        context = emptyList(),
    )

    suspend fun addAnswer(
        conversationId: UUID,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
    ): Message? {
        val message = messageRepo.addMessage(
            conversationId = conversationId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
        ) ?: return null

        citationRepo.addCitations(UUID.fromString(message.id), citations)
        return message
    }

    suspend fun updateAnswer(
        messageId: UUID,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
    ): Message? {
        val message = messageRepo.updateMessage(
            messageId = messageId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
        ) ?: return null

        // TODO this will create duplicates.
        citationRepo.addCitations(UUID.fromString(message.id), citations)
        return message
    }

    suspend fun getMessage(messageId: UUID): Message? =
        messageRepo.getMessage(messageId)

    suspend fun addFeedbackToMessage(messageId: UUID, newFeedback: NewFeedback): Message? {
        return messageRepo.addFeedback(messageId, newFeedback)
    }
}

fun Route.messageRoutes(messageService: MessageService) {
    route("/messages") {
        get("/{id}", {
            description = "Get a message with the given ID"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<Message> {
                        description = "The message requested"
                    }
                }
            }
        }) {
            val messageId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val message = messageService.getMessage(messageId)
            if (message == null) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.respond(message)
        }
        post("/{id}/feedback", {
            description = "Create a new feedback for a message"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
                body<NewFeedback> {
                    description = "The feedback to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The feedback was created"
                    body<Feedback> {
                        description = "The feedback that got created"
                    }
                }
            }
        }) {
            val messageId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val feedback = call.receiveNullable<NewFeedback>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val message = messageService.addFeedbackToMessage(messageId, feedback)
            if (message == null) {
                return@post call.respond(HttpStatusCode.NotFound)
            }

            call.respond(HttpStatusCode.Created, message)
        }
    }
}
