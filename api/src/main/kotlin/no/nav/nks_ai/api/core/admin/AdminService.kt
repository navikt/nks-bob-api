package no.nav.nks_ai.api.core.admin

import arrow.core.raise.either
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.ConversationRepo
import no.nav.nks_ai.api.core.conversation.ConversationSummary
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageRepo

class AdminService() {
    suspend fun getConversation(conversationId: ConversationId): ApplicationResult<Conversation> =
        ConversationRepo.getConversation(conversationId)

    suspend fun getConversationSummary(conversationId: ConversationId): ApplicationResult<ConversationSummary> =
        either {
            val conversation = ConversationRepo.getConversation(conversationId).bind()
            val messages = MessageRepo.getMessagesByConversation(conversationId).bind()

            ConversationSummary.Companion.from(conversation, messages)
        }

    suspend fun getConversationMessages(conversationId: ConversationId): ApplicationResult<List<Message>> =
        either {
            ConversationRepo.getConversation(conversationId).bind()
            MessageRepo.getMessagesByConversation(conversationId).bind()
        }

    suspend fun getConversationFromMessageId(messageId: MessageId): ApplicationResult<Conversation> =
        either {
            val conversationId = MessageRepo.getConversationId(messageId).bind()

            ConversationRepo.getConversation(conversationId).bind()
        }
}
