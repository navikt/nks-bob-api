package no.nav.nks_ai.core.admin

import arrow.core.raise.either
import no.nav.nks_ai.app.ApplicationResult
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

    suspend fun getConversation(conversationId: ConversationId): ApplicationResult<Conversation> =
        ConversationRepo.getConversation(conversationId)

    suspend fun getConversationSummary(conversationId: ConversationId): ApplicationResult<ConversationSummary> =
        either {
            val conversation = ConversationRepo.getConversation(conversationId).bind()
            val messages = MessageRepo.getMessagesByConversation(conversationId).sortedBy { it.createdAt }

            ConversationSummary.from(conversation, messages)
        }

    suspend fun getConversationMessages(conversationId: ConversationId): List<Message> =
        MessageRepo.getMessagesByConversation(conversationId).sortedBy { it.createdAt }

    suspend fun getConversationFromMessageId(messageId: MessageId): ApplicationResult<Conversation> =
        either {
            val conversationId = MessageRepo.getConversationId(messageId).bind()

            ConversationRepo.getConversation(conversationId).bind()
        }
}
