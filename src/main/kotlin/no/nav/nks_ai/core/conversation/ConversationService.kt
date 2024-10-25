package no.nav.nks_ai.core.conversation

import arrow.core.Either
import arrow.core.raise.either
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRepo
import no.nav.nks_ai.core.user.NavIdent

class ConversationService(
) {
    suspend fun addConversation(navIdent: NavIdent, conversation: NewConversation) =
        ConversationRepo.addConversation(navIdent, conversation)

    // TODO metrics
    suspend fun getConversation(
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): Either<ConversationError.ConversationNotFound, Conversation> =
        either {
            ConversationRepo.getConversation(conversationId, navIdent)
                ?: raise(ConversationError.ConversationNotFound(conversationId))
        }

    suspend fun getAllConversations(navIdent: NavIdent): List<Conversation> =
        ConversationRepo.getAllConversations(navIdent)

    suspend fun getConversationMessages(
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): List<Message>? {
        ConversationRepo.getConversation(conversationId, navIdent)
            ?: return null

        return MessageRepo.getMessagesByConversation(conversationId)
            .sortedBy { it.createdAt }
    }

    suspend fun deleteConversation(conversationId: ConversationId, navIdent: NavIdent): Unit =
        ConversationRepo.deleteConversation(conversationId, navIdent)

    suspend fun updateConversation(id: ConversationId, navIdent: NavIdent, conversation: UpdateConversation) =
        ConversationRepo.updateConversation(id, navIdent, conversation)
}

