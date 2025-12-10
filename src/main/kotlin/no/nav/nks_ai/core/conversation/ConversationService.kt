package no.nav.nks_ai.core.conversation

import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRepo
import no.nav.nks_ai.core.user.NavIdent

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

    suspend fun deleteOldConversations(deleteBefore: LocalDateTime): ApplicationResult<Unit> = either {
        val conversations = ConversationRepo.getConversationsCreatedBefore(deleteBefore).bind()
            .filter { conversation ->
                MessageRepo.conversationHasMessages(conversation.id).bind()
            }

        if (conversations.isEmpty()) {
            logger.info { "Found 0 conversations older than $deleteBefore." }
            return@either
        }

        logger.info { "Deleting ${conversations.size} conversations older than $deleteBefore" }
        val deletedCount = ConversationRepo.deleteConversations(conversations.map { it.id }).bind()
        logger.info { "$deletedCount conversations deleted" }
    }
}

