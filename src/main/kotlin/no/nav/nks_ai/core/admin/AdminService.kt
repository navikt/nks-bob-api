package no.nav.nks_ai.core.admin

import arrow.core.Either
import arrow.core.raise.either
import no.nav.nks_ai.app.DomainError
import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationRepo
import no.nav.nks_ai.core.conversation.ConversationSummary
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageRepo
import no.nav.nks_ai.core.user.NavIdent

class AdminService() {
    suspend fun deleteAllConversations(navIdent: NavIdent) {
        ConversationRepo.deleteAllConversations(navIdent)
    }

    suspend fun deleteConversation(conversationId: ConversationId, navIdent: NavIdent) {
        ConversationRepo.deleteConversation(conversationId, navIdent)
    }

    suspend fun getAllConversations(navIdent: NavIdent): List<Conversation> =
        ConversationRepo.getAllConversations(navIdent)

    suspend fun getConversationSummary(conversationId: ConversationId): Either<DomainError, ConversationSummary> =
        either {
            val conversation = ConversationRepo.getConversation(conversationId)
                ?: raise(DomainError.ConversationNotFound(conversationId))
            val messages = MessageRepo.getMessagesByConversation(conversationId).sortedBy { it.createdAt }

            ConversationSummary.from(conversation, messages)
        }

    suspend fun getConversationMessages(conversationId: ConversationId): List<Message> =
        MessageRepo.getMessagesByConversation(conversationId).sortedBy { it.createdAt }

    suspend fun getConversationFromMessageId(messageId: MessageId): Either<DomainError, Conversation> =
        either {
            val conversationId = MessageRepo.getConversationId(messageId).bind()

            ConversationRepo.getConversation(conversationId)
                ?: raise(DomainError.ConversationNotFound(conversationId))
        }
}
