package no.nav.nks_ai.core.message

import arrow.core.Either
import arrow.core.Some
import arrow.core.some
import no.nav.nks_ai.app.DomainError
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.user.NavIdent

class MessageService() {
    suspend fun addQuestion(
        conversationId: ConversationId,
        navIdent: NavIdent,
        messageContent: String,
    ): Either<DomainError, Message> {
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
    ): Either<DomainError, Message> {
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

    suspend fun addEmptyAnswer(conversationId: ConversationId): Either<DomainError, Message> =
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

    suspend fun updatePendingMessage(
        messageId: MessageId,
        pending: Boolean,
    ): Either<DomainError, Message> {
        return MessageRepo.patchMessage(
            messageId = messageId,
            pending = Some(pending),
        )
    }

    suspend fun starMessage(messageId: MessageId) =
        MessageRepo.patchMessage(messageId = messageId, starred = true.some())

    suspend fun markStarredMessageUploaded(messageId: MessageId) =
        MessageRepo.markStarredMessageUploaded(messageId)

    suspend fun getStarredMessagesNotUploaded(): List<Message> =
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

    suspend fun addFeedbackToMessage(messageId: MessageId, newFeedback: NewFeedback): Either<DomainError, Message> {
        if (newFeedback.liked) {
            MetricRegister.answersLiked.inc()
        } else {
            MetricRegister.answersDisliked.inc()
        }

        return MessageRepo.addFeedback(messageId, newFeedback)
    }

    suspend fun updateMessage(messageId: MessageId, message: UpdateMessage): Either<DomainError, Message> =
        MessageRepo.patchMessage(
            messageId = messageId,
            starred = message.starred.some(),
        )
}