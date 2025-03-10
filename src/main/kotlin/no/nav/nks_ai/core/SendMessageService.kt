package no.nav.nks_ai.core

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import no.nav.nks_ai.app.DomainError
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageError
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.NewCitation
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.core.message.answerFrom
import no.nav.nks_ai.core.user.NavIdent
import no.nav.nks_ai.kbs.KbsChatMessage
import no.nav.nks_ai.kbs.KbsChatResponse
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.kbs.KbsErrorResponse
import no.nav.nks_ai.kbs.fromMessage
import no.nav.nks_ai.kbs.toModel
import no.nav.nks_ai.kbs.toNewCitation

private val logger = KotlinLogging.logger { }

class SendMessageService(
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val kbsClient: KbsClient
) {
    suspend fun sendMessageStream(
        message: NewMessage,
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): Either<DomainError, Flow<Message>> = either {
        val history = conversationService.getConversationMessages(conversationId, navIdent).bind()
        val question = messageService.addQuestion(conversationId, navIdent, message.content).bind()
        val initialAnswer = messageService.addEmptyAnswer(conversationId).bind()
        val timer = MetricRegister.answerFinishedReceived()
        flow {
            // Start the flow with the question and the empty answer.
            emit(question)
            emit(initialAnswer)

            var latestMessage: Either<MessageError, Message> = initialAnswer.right()

            kbsClient.sendQuestionStream(
                question = message.content,
                messageHistory = history.map(KbsChatMessage::fromMessage),
            )
                .conflate()
                .map { responseToMessage(it, initialAnswer) }
                .onEach { latestMessage = it }
                .collect { response ->
                    response.onRight { message ->
                        emit(message)
                    }
                }

            latestMessage.map { message ->
                messageService.updateAnswer(
                    messageId = message.id,
                    messageContent = message.content,
                    citations = message.citations.map { NewCitation(it.text, it.sourceId) },
                    context = message.context,
                    followUp = message.followUp,
                    pending = false,
                    userQuestion = message.userQuestion,
                    contextualizedQuestion = message.contextualizedQuestion,
                ).map { emit(it) }.bind()
            }.mapLeft { error ->
                MetricRegister.answerFailedReceive.inc()
                messageService.updateMessageError(
                    messageId = initialAnswer.id,
                    errors = listOf(error),
                    pending = false,
                ).map { emit(it) }.bind()
            }
        }.catch { error ->
            handleKbsError(error, initialAnswer)
                .map { emit(it) }.bind()
        }
            .filterNotNull()
            .onCompletion { timer.stop() }
    }

    private suspend fun handleKbsError(
        error: Throwable,
        initialAnswer: Message,
    ): Either<DomainError, Message> {
        logger.error(error) { "Error when receiving answer from KBS" }
        MetricRegister.answerFailedReceive.inc()
        return messageService.updateMessageError(
            messageId = initialAnswer.id,
            pending = false,
            errors = listOf(
                MessageError(
                    title = "Ukjent feil",
                    description = "En ukjent feil oppsto når vi mottok svar fra språkmodellen."
                )
            ),
        )
    }
}

private fun responseToMessage(
    kbsResponse: Either<KbsErrorResponse, KbsChatResponse>,
    initialAnswer: Message
): Either<MessageError, Message> = kbsResponse
    .mapLeft { error ->
        MessageError(
            title = error.title,
            description = error.detail,
        )
    }.map { response ->
        val answerContent = response.answer.text
        val citations = response.answer.citations.map { it.toNewCitation() }
        val context = response.context.map { it.toModel() }

        Message.answerFrom(
            messageId = initialAnswer.id,
            content = answerContent,
            citations = citations,
            context = context,
            followUp = response.followUp,
            userQuestion = response.question.user,
            contextualizedQuestion = response.question.contextualized,
        )
    }
