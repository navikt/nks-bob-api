package no.nav.nks_ai.core.admin

import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationRepo
import java.util.UUID

class AdminService(
) {
    suspend fun deleteAllConversations(navIdent: String) {
        ConversationRepo.deleteAllConversations(navIdent)
    }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String) {
        ConversationRepo.deleteConversation(conversationId, navIdent)
    }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        ConversationRepo.getAllConversations(navIdent)
}
