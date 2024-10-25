package no.nav.nks_ai.core.admin

import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationRepo
import no.nav.nks_ai.core.user.NavIdent

class AdminService(
) {
    suspend fun deleteAllConversations(navIdent: NavIdent) {
        ConversationRepo.deleteAllConversations(navIdent)
    }

    suspend fun deleteConversation(conversationId: ConversationId, navIdent: NavIdent) {
        ConversationRepo.deleteConversation(conversationId, navIdent)
    }

    suspend fun getAllConversations(navIdent: NavIdent): List<Conversation> =
        ConversationRepo.getAllConversations(navIdent)
}
