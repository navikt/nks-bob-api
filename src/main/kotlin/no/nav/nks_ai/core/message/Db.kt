package no.nav.nks_ai.core.message

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.either
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
import no.nav.nks_ai.core.conversation.ConversationDAO
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.Conversations
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

val jsonConfig = Json {
    ignoreUnknownKeys = true
}

internal object Messages : UUIDTable() {
    val content = text("content", eagerLoading = true)
    val conversation = reference("conversation", Conversations)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val messageType = enumeration<MessageType>("message_type")
    val messageRole = enumeration<MessageRole>("message_role")
    val createdBy = varchar("created_by", 255)
    val context = jsonb<List<Context>>("context", jsonConfig).clientDefault { emptyList() }
    val citations = jsonb<List<Citation>>("citations", jsonConfig).clientDefault { emptyList() }
    val pending = bool("pending").clientDefault { false }
    val errors = jsonb<List<MessageError>>("errors", jsonConfig).clientDefault { emptyList() }
    val followUp = jsonb<List<String>>("follow_up", jsonConfig).clientDefault { emptyList() }
    val userQuestion = text("user_question").nullable()
    val contextualizedQuestion = text("contextualized_question").nullable()
    val starred = bool("starred").clientDefault { false }
    val starredUploadedAt = datetime("starred_uploaded_at").nullable()
}

internal class MessageDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MessageDAO>(Messages)

    var content by Messages.content
    var conversation by ConversationDAO.Companion referencedOn Messages.conversation
    var citations by Messages.citations
    var createdAt by Messages.createdAt
    var messageType by Messages.messageType
    var messageRole by Messages.messageRole
    var createdBy by Messages.createdBy
    var context by Messages.context
    var pending by Messages.pending
    var errors by Messages.errors
    var followUp by Messages.followUp
    var userQuestion by Messages.userQuestion
    var contextualizedQuestion by Messages.contextualizedQuestion
    var starred by Messages.starred
    var starredUploadedAt by Messages.starredUploadedAt
}

internal fun MessageDAO.toModel() = Message(
    id = id.value.toMessageId(),
    content = content,
    createdAt = createdAt,
    messageType = messageType,
    messageRole = messageRole,
    citations = citations,
    context = context,
    pending = pending,
    errors = errors,
    followUp = followUp,
    userQuestion = userQuestion,
    contextualizedQuestion = contextualizedQuestion,
    starred = starred,
)

object MessageRepo {
    suspend fun addMessage(
        conversationId: ConversationId,
        messageContent: String,
        messageType: MessageType,
        messageRole: MessageRole,
        createdBy: String,
        context: List<Context>,
        citations: List<Citation>,
        pending: Boolean,
    ): Either<ApplicationError, Message> =
        suspendTransaction {
            either {
                val conversation = ConversationDAO.Companion.findById(conversationId.value)
                    ?: raise(ApplicationError.ConversationNotFound(conversationId))

                MessageDAO.new {
                    this.content = messageContent
                    this.conversation = conversation
                    this.messageType = messageType
                    this.messageRole = messageRole
                    this.createdBy = createdBy
                    this.context = context
                    this.citations = citations
                    this.pending = pending
                }.toModel()
            }
        }

    suspend fun patchMessage(
        messageId: MessageId,
        messageContent: Option<String> = None,
        messageType: Option<MessageType> = None,
        messageRole: Option<MessageRole> = None,
        createdBy: Option<String> = None,
        context: Option<List<Context>> = None,
        citations: Option<List<Citation>> = None,
        pending: Option<Boolean> = None,
        errors: Option<List<MessageError>> = None,
        starred: Option<Boolean> = None,
    ): Either<ApplicationError, Message> =
        suspendTransaction {
            either {
                MessageDAO.findByIdAndUpdate(messageId.value) { entity ->
                    messageContent.onSome { entity.content = it }
                    messageType.onSome { entity.messageType = it }
                    messageRole.onSome { entity.messageRole = it }
                    createdBy.onSome { entity.createdBy = it }
                    context.onSome { entity.context = it }
                    citations.onSome { entity.citations = it }
                    pending.onSome { entity.pending = it }
                    errors.onSome { entity.errors = entity.errors.plus(it) }
                    starred.onSome { entity.starred = it }
                }?.toModel()
                    ?: raise(ApplicationError.MessageNotFound(messageId))
            }
        }

    suspend fun updateMessage(
        messageId: MessageId,
        messageContent: String,
        messageType: MessageType,
        messageRole: MessageRole,
        createdBy: String,
        context: List<Context>,
        citations: List<Citation>,
        followUp: List<String>,
        pending: Boolean,
        userQuestion: String?,
        contextualizedQuestion: String?,
    ): Either<ApplicationError, Message> =
        suspendTransaction {
            either {
                MessageDAO.findByIdAndUpdate(messageId.value) {
                    it.content = messageContent
                    it.messageType = messageType
                    it.messageRole = messageRole
                    it.createdBy = createdBy
                    it.context = context
                    it.citations = citations
                    it.followUp = followUp
                    it.pending = pending
                    it.userQuestion = userQuestion
                    it.contextualizedQuestion = contextualizedQuestion
                }?.toModel()
                    ?: raise(ApplicationError.MessageNotFound(messageId))
            }
        }

    suspend fun getMessage(messageId: MessageId): Either<ApplicationError, Message> =
        suspendTransaction {
            either {
                MessageDAO.findById(messageId.value)
                    ?.toModel()
                    ?: raise(ApplicationError.MessageNotFound(messageId))
            }
        }

    suspend fun getMessagesByConversation(conversationId: ConversationId): List<Message> =
        suspendTransaction {
            MessageDAO.find { Messages.conversation eq conversationId.value }
                .sortedBy { Messages.createdAt }
                .map { it.toModel() }
        }

    suspend fun getConversationId(messageId: MessageId): Either<ApplicationError, ConversationId> =
        suspendTransaction {
            either {
                MessageDAO.findById(messageId.value)?.conversation
                    ?.let { conversation ->
                        ConversationId(conversation.id.value)
                    } ?: raise(ApplicationError.MessageNotFound(messageId))
            }
        }

    suspend fun markStarredMessageUploaded(messageId: MessageId): Either<ApplicationError, Message> =
        suspendTransaction {
            either {
                MessageDAO
                    .findByIdAndUpdate(messageId.value) {
                        it.starredUploadedAt = LocalDateTime.now()
                    }?.toModel()
                    ?: raise(ApplicationError.MessageNotFound(messageId))
            }
        }

    suspend fun getStarredMessagesNotUploaded(): List<Message> =
        suspendTransaction {
            MessageDAO
                .find {
                    Messages.starredUploadedAt.isNull().and {
                        Messages.starred.eq(true)
                    }
                }.map(MessageDAO::toModel)
        }

    suspend fun getOwner(messageId: MessageId): ApplicationResult<String> =
        suspendTransaction {
            either {
                MessageDAO.findById(messageId.value)?.conversation?.owner
                    ?: raise(ApplicationError.MessageNotFound(messageId))
            }
        }
}