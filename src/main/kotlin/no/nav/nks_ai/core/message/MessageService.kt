package no.nav.nks_ai.core.message

import arrow.core.Some
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.some
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.user.NavIdent

private val logger = KotlinLogging.logger { }

class MessageService() {
    suspend fun addQuestion(
        conversationId: ConversationId,
        navIdent: NavIdent,
        messageContent: String,
    ): ApplicationResult<Message> {
        MetricRegister.questionsCreated.inc()
        return MessageRepo.addMessage(
            conversationId = conversationId,
            messageContent = messageContent,
            createdBy = navIdent.hash,
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            context = emptyList(),
            citations = emptyList(),
            pending = false,
        )
    }

    suspend fun addAnswer(
        conversationId: ConversationId,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
    ): ApplicationResult<Message> {
        MetricRegister.answersCreated.inc()
        return MessageRepo.addMessage(
            conversationId = conversationId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
            citations = citations.map(Citation::fromNewCitation),
            pending = true,
        )
    }

    suspend fun addEmptyAnswer(conversationId: ConversationId): ApplicationResult<Message> =
        addAnswer(
            conversationId = conversationId,
            messageContent = "",
            citations = emptyList(),
            context = emptyList()
        )

    suspend fun updateAnswer(
        messageId: MessageId,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
        followUp: List<String>,
        pending: Boolean,
        userQuestion: String?,
        contextualizedQuestion: String?,
    ) =
        MessageRepo.updateMessage(
            messageId = messageId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
            citations = citations.map(Citation::fromNewCitation),
            followUp = followUp,
            pending = pending,
            userQuestion = userQuestion,
            contextualizedQuestion = contextualizedQuestion,
        )

    suspend fun markStarredMessageUploaded(messageId: MessageId) =
        MessageRepo.markStarredMessageUploaded(messageId)

    suspend fun getStarredMessagesNotUploaded(): ApplicationResult<List<Message>> =
        MessageRepo.getStarredMessagesNotUploaded()

    suspend fun updateMessageError(
        messageId: MessageId,
        errors: List<MessageError>,
        pending: Boolean = false
    ) =
        MessageRepo.patchMessage(
            messageId = messageId,
            errors = Some(errors),
            pending = Some(pending),
        )

    suspend fun getMessage(messageId: MessageId) =
        MessageRepo.getMessage(messageId)

    suspend fun getMessage(messageId: MessageId, navIdent: NavIdent): ApplicationResult<Message> =
        either {
            ensure(isOwnedBy(messageId, navIdent).bind()) { ApplicationError.MissingAccess() }
            getMessage(messageId).bind()
        }

    suspend fun isOwnedBy(messageId: MessageId, navIdent: NavIdent): ApplicationResult<Boolean> = either {
        val ownedBy = MessageRepo.getOwner(messageId).bind()
        navIdent.isVerified(ownedBy)
    }

    suspend fun updateMessage(messageId: MessageId, message: UpdateMessage): ApplicationResult<Message> =
        MessageRepo.patchMessage(
            messageId = messageId,
            starred = message.starred.some(),
        )

    suspend fun deleteOldMessages(deleteBefore: LocalDateTime): ApplicationResult<Unit> = either {
        val messages = MessageRepo.getMessagesCreatedBefore(deleteBefore).bind()
        if (messages.isEmpty()) {
            logger.info { "Found 0 messages older than $deleteBefore." }
            return@either
        }

        logger.info { "Deleting ${messages.size} messages older than $deleteBefore" }
        val deletedCount = MessageRepo.deleteMessages(messages.map { it.id }).bind()
        logger.info { "$deletedCount messages deleted" }
    }
}