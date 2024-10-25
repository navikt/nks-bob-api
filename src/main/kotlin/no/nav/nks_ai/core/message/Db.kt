package no.nav.nks_ai.core.message

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
import no.nav.nks_ai.core.conversation.ConversationDAO
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.Conversations
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

val jsonConfig = Json.Default

internal object Messages : UUIDTable() {
    val content = text("content", eagerLoading = true)
    val conversation = reference("conversation", Conversations)
    val feedback = jsonb<Feedback>("feedback", jsonConfig).nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val messageType = enumeration<MessageType>("message_type")
    val messageRole = enumeration<MessageRole>("message_role")
    val createdBy = varchar("created_by", 255)
    val context = jsonb<List<Context>>("context", jsonConfig).clientDefault { emptyList() }
    val citations = jsonb<List<Citation>>("citations", jsonConfig).clientDefault { emptyList() }
}

internal class MessageDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MessageDAO>(Messages)

    var content by Messages.content
    var conversation by ConversationDAO.Companion referencedOn Messages.conversation
    var feedback by Messages.feedback
    var citations by Messages.citations
    var createdAt by Messages.createdAt
    var messageType by Messages.messageType
    var messageRole by Messages.messageRole
    var createdBy by Messages.createdBy
    var context by Messages.context
}

internal fun MessageDAO.toModel() = Message(
    id = id.value.toMessageId(),
    content = content,
    createdAt = createdAt,
    feedback = feedback,
    messageType = messageType,
    messageRole = messageRole,
    createdBy = createdBy,
    citations = citations,
    context = context
)

object MessageRepo {
    suspend fun addMessage(
        conversationId: ConversationId,
        messageContent: String,
        messageType: MessageType,
        messageRole: MessageRole,
        createdBy: String,
        context: List<Context>,
        citations: List<Citation>
    ): Message? =
        suspendTransaction {
            val conversation = ConversationDAO.Companion.findById(conversationId.value)
                ?: return@suspendTransaction null // TODO error

            MessageDAO.new {
                this.content = messageContent
                this.conversation = conversation
                this.messageType = messageType
                this.messageRole = messageRole
                this.createdBy = createdBy
                this.context = context
                this.citations = citations
            }.toModel()
        }

    suspend fun updateMessage(
        messageId: MessageId,
        messageContent: String,
        messageType: MessageType,
        messageRole: MessageRole,
        createdBy: String,
        context: List<Context>,
        citations: List<Citation>,
    ): Message? =
        suspendTransaction {
            MessageDAO.findByIdAndUpdate(messageId.value) {
                it.content = messageContent
                it.messageType = messageType
                it.messageRole = messageRole
                it.createdBy = createdBy
                it.context = context
                it.citations = citations
            }?.toModel()
        }

    suspend fun getMessage(id: MessageId): Message? =
        suspendTransaction {
            MessageDAO.findById(id.value)
                ?.toModel()
        }

    suspend fun getMessagesByConversation(conversationId: ConversationId): List<Message> =
        suspendTransaction {
            MessageDAO.find { Messages.conversation eq conversationId.value }
                .sortedBy { Messages.createdAt }
                .map { it.toModel() }
        }

    suspend fun addFeedback(messageId: MessageId, newFeedback: NewFeedback): Message? =
        suspendTransaction {
            MessageDAO.findByIdAndUpdate(messageId.value) {
                it.feedback = Feedback.fromNewFeedback(newFeedback)
            }?.toModel()
        }
}