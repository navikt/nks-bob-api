package no.nav.nks_ai.core.conversation

import arrow.core.Either
import arrow.core.raise.either
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRepo
import java.util.UUID

class ConversationService(
) {
    suspend fun addConversation(navIdent: String, conversation: NewConversation) =
        ConversationRepo.addConversation(navIdent, conversation)

    // TODO metrics
    suspend fun getConversation(
        conversationId: UUID,
        navIdent: String
    ): Either<ConversationError.ConversationNotFound, Conversation> =
        either {
            ConversationRepo.getConversation(conversationId, navIdent)
                ?: raise(ConversationError.ConversationNotFound(conversationId))
        }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        ConversationRepo.getAllConversations(navIdent)

    suspend fun getConversationMessages(
        conversationId: UUID,
        navIdent: String
    ): List<Message>? {
        ConversationRepo.getConversation(conversationId, navIdent)
            ?: return null

        return MessageRepo.getMessagesByConversation(conversationId)
            .sortedBy { it.createdAt }
    }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String): Unit =
        ConversationRepo.deleteConversation(conversationId, navIdent)

    suspend fun updateConversation(id: UUID, navIdent: String, conversation: UpdateConversation) =
        ConversationRepo.updateConversation(id, navIdent, conversation)
}

