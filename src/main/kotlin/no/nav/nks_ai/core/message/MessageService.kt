package no.nav.nks_ai.core.message

import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.user.NavIdent

class MessageService() {
    suspend fun addQuestion(
        conversationId: ConversationId,
        navIdent: NavIdent,
        messageContent: String,
    ) = MessageRepo.addMessage(
        conversationId = conversationId,
        messageContent = messageContent,
        createdBy = navIdent.value,
        messageType = MessageType.Question,
        messageRole = MessageRole.Human,
        context = emptyList(),
        citations = emptyList(),
    )

    suspend fun addAnswer(
        conversationId: ConversationId,
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

    suspend fun addEmptyAnswer(conversationId: ConversationId): Message? =
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

    suspend fun getMessage(messageId: MessageId): Message? =
        MessageRepo.getMessage(messageId)

    suspend fun addFeedbackToMessage(messageId: MessageId, newFeedback: NewFeedback): Message? {
        return MessageRepo.addFeedback(messageId, newFeedback)
    }
}