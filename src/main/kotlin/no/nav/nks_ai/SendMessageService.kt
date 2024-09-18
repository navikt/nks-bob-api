package no.nav.nks_ai

import no.nav.nks_ai.citation.NewCitation
import no.nav.nks_ai.conversation.ConversationService
import no.nav.nks_ai.kbs.KbsChatMessage
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.kbs.fromMessage
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
        val citations = response.answer.citations.map {
            NewCitation(
                text = it.text,
                article = it.article,
                title = it.title,
                section = it.section,
            )
        }

        val newMessage = messageService.addAnswer(conversationId, answerContent, citations)
        return newMessage
    }
}