package no.nav.nks_ai.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.produceIn
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.kbs.KbsChatMessage
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.kbs.fromMessage
import no.nav.nks_ai.kbs.toModel
import no.nav.nks_ai.kbs.toNewCitation

private val logger = KotlinLogging.logger { }

class SendMessageService(
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val kbsClient: KbsClient
) {
    suspend fun sendMessage(
        message: NewMessage,
        conversationId: ConversationId,
        navIdent: String,
    ): Message? {
        val history = conversationService.getConversationMessages(conversationId, navIdent) ?: return null
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
        conversationId: ConversationId,
        navIdent: String,
    ): Message? {
        val history = conversationService.getConversationMessages(conversationId, navIdent) ?: return null
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
            messageId = emptyMessage.id,
            messageContent = answerContent,
            citations = citations,
            context = context,
        )
    }

    suspend fun sendMessageStream(
        message: NewMessage,
        conversationId: ConversationId,
        navIdent: String,
    ): Flow<Message> {
        val history = conversationService.getConversationMessages(conversationId, navIdent)
            ?: return emptyFlow()

        val question = messageService.addQuestion(conversationId, navIdent, message.content)
            ?: return emptyFlow()

        val initialAnswer = messageService.addEmptyAnswer(conversationId)
            ?: return emptyFlow()

        return kbsClient.sendQuestionStream(
            question = message.content,
            messageHistory = history.map(KbsChatMessage::fromMessage),
        ).conflate().map { response ->
            val answerContent = response.answer.text
            val citations = response.answer.citations.map { it.toNewCitation() }
            val context = response.context.map { it.toModel() }

            messageService.updateAnswer(
                messageId = initialAnswer.id,
                messageContent = answerContent,
                citations = citations,
                context = context,
            )
        }.filterNotNull().onStart {
            // Start the flow with the question and the empty answer.
            emit(question)
            emit(initialAnswer)
        }
    }

    suspend fun sendMessageChannel(
        message: NewMessage,
        conversationId: ConversationId,
        navIdent: String,
    ): ReceiveChannel<Message> =
        sendMessageStream(message = message, conversationId = conversationId, navIdent = navIdent)
            .produceIn(CoroutineScope(Dispatchers.IO))
}