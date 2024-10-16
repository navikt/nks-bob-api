package no.nav.nks_ai

import no.nav.nks_ai.conversation.ConversationService
import no.nav.nks_ai.kbs.KbsChatMessage
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.kbs.fromMessage
import no.nav.nks_ai.kbs.toModel
import no.nav.nks_ai.kbs.toNewCitation
import no.nav.nks_ai.message.Message
import no.nav.nks_ai.message.MessageService
import no.nav.nks_ai.message.NewMessage
import java.util.UUID

class SendMessageService(
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val kbsClient: KbsClient
) {
    suspend fun sendMessage(
        message: NewMessage,
        conversationId: UUID,
        navIdent: String,
    ): Message? {
        val history = conversationService.getConversationMessages(conversationId, navIdent)
        messageService.addQuestion(conversationId, navIdent, message.content)

        val response = kbsClient.sendQuestion(
            question = message.content,
            messageHistory = history.map(KbsChatMessage::fromMessage),
        ) ?: return null

        val answerContent = response.answer.text
        val citations = response.answer.citations.map { it.toNewCitation() }
        val context = response.context.map { it.toModel() }

        return messageService.addAnswer(
            conversationId = conversationId,
            messageContent = answerContent,
            citations = citations,
            context = context,
        )
    }

    suspend fun sendMessageDelayed(
        message: NewMessage,
        conversationId: UUID,
        navIdent: String,
    ): Message? {
        val history = conversationService.getConversationMessages(conversationId, navIdent)
        messageService.addQuestion(conversationId, navIdent, message.content)

        val emptyMessage = messageService.addAnswer(
            conversationId,
            "",
            emptyList(),
            emptyList()
        ) ?: return null

        val response = kbsClient.sendQuestion(
            question = message.content,
            messageHistory = history.map(KbsChatMessage::fromMessage),
        ) ?: return null

        val answerContent = response.answer.text
        val citations = response.answer.citations.map { it.toNewCitation() }
        val context = response.context.map { it.toModel() }

        return messageService.updateAnswer(
            messageId = UUID.fromString(emptyMessage.id),
            messageContent = answerContent,
            citations = citations,
            context = context,
        )
    }
}