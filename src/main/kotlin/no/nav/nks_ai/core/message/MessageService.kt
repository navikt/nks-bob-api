package no.nav.nks_ai.core.message

import java.util.UUID

class MessageService() {
    suspend fun addQuestion(
        conversationId: UUID,
        navIdent: String,
        messageContent: String,
    ) = MessageRepo.addMessage(
        conversationId = conversationId,
        messageContent = messageContent,
        createdBy = navIdent,
        messageType = MessageType.Question,
        messageRole = MessageRole.Human,
        context = emptyList(),
        citations = emptyList(),
    )

    suspend fun addAnswer(
        conversationId: UUID,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
    ): Message? {
        return MessageRepo.addMessage(
            conversationId = conversationId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
            citations = citations.map(Citation::fromNewCitation),
        )
    }

    suspend fun updateAnswer(
        messageId: UUID,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
    ): Message? {
        return MessageRepo.updateMessage(
            messageId = messageId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
            citations = citations.map(Citation::fromNewCitation),
        )
    }

    suspend fun getMessage(messageId: UUID): Message? =
        MessageRepo.getMessage(messageId)

    suspend fun addFeedbackToMessage(messageId: UUID, newFeedback: NewFeedback): Message? {
        return MessageRepo.addFeedback(messageId, newFeedback)
    }
}