package no.nav.nks_ai.api.core.conversation

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageRepo
import no.nav.nks_ai.api.core.user.NavIdent

private val logger = KotlinLogging.logger { }

class ConversationService(
) {
    suspend fun addConversation(navIdent: NavIdent, conversation: NewConversation): ApplicationResult<Conversation> {
        MetricRegister.conversationsCreated.inc()
        return ConversationRepo.addConversation(navIdent, conversation)
    }

    suspend fun getConversation(
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): ApplicationResult<Conversation> =
        ConversationRepo.getConversation(conversationId, navIdent)

    suspend fun getAllConversations(navIdent: NavIdent): ApplicationResult<List<Conversation>> =
        ConversationRepo.getAllConversations(navIdent)

    suspend fun getConversationMessages(
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): ApplicationResult<List<Message>> = either {
        ConversationRepo.getConversation(conversationId, navIdent).bind()

        MessageRepo.getMessagesByConversation(conversationId).bind()
    }

    suspend fun deleteConversation(conversationId: ConversationId, navIdent: NavIdent): ApplicationResult<Unit> =
        ConversationRepo.deleteConversation(conversationId, navIdent)

    suspend fun updateConversation(id: ConversationId, navIdent: NavIdent, conversation: UpdateConversation) =
        ConversationRepo.updateConversation(id, navIdent, conversation)

    suspend fun deleteOldConversations(deleteBefore: LocalDateTime): ApplicationResult<Int> = either {
        val deletedCount = ConversationRepo.deleteEmptyConversationsCreatedBefore(deleteBefore).bind()
        logger.info { "$deletedCount empty conversations older than $deleteBefore deleted" }
        deletedCount
    }
}

