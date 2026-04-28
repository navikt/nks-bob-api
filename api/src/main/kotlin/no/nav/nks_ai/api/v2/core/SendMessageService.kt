package no.nav.nks_ai.api.v2.core

import arrow.core.left
import arrow.core.none
import arrow.core.raise.either
import arrow.core.some
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageError
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.MessageType
import no.nav.nks_ai.api.core.message.NewCitation
import no.nav.nks_ai.api.core.message.Tool
import no.nav.nks_ai.api.core.message.answerFrom
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.api.kbs.KbsChatMessage
import no.nav.nks_ai.api.kbs.KbsErrorResponse
import no.nav.nks_ai.api.kbs.fromMessage
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEvent
import no.nav.nks_ai.api.v2.core.conversation.streaming.diff
import no.nav.nks_ai.api.v2.kbs.KbsClient
import no.nav.nks_ai.api.v2.kbs.KbsStreamResponse
import no.nav.nks_ai.api.v2.kbs.toModel

private val logger = KotlinLogging.logger { }

class SendMessageService(
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val kbsClient: KbsClient
) {
    suspend fun askQuestion(
        question: Message,
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): ApplicationResult<Flow<ConversationEvent>> = either {
        if (question.messageType != MessageType.Question) {
            return ApplicationError.InvalidInput("Invalid input", "Provided message is not a question").left()
        }

        val history = conversationService.getConversationMessages(conversationId, navIdent).bind()
            .map(KbsChatMessage::fromMessage)

        val initialAnswer = messageService.addEmptyAnswer(conversationId).bind()
        val messageId = initialAnswer.id

        val timer = MetricRegister.answerFinishedReceived()
        channelFlow {
            send(ConversationEvent.NewMessage(messageId, initialAnswer))

            var latestMessage = initialAnswer
            kbsClient.sendQuestionStream(
                question = question.content,
                messageHistory = history,
            )
                .runningFold(none<Pair<ConversationEvent, Message>>()) { prev, result ->
                    result.fold(
                        ifRight = { response ->
                            when (response) {
                                is KbsStreamResponse.KbsTokenChunkResponse -> {
                                    val event = ConversationEvent.ContentUpdated(messageId, response.chunk)
                                    val message = prev.fold(
                                        ifEmpty = { initialAnswer.copy(content = response.chunk) },
                                        ifSome = { (_, prevMessage) -> prevMessage.copy(content = prevMessage.content + response.chunk) },
                                    )

                                    (event to message)
                                }

                                is KbsStreamResponse.StatusUpdateResponse -> {
                                    val event = ConversationEvent.StatusUpdate(messageId, response.text)
                                    val message = prev.fold(
                                        ifEmpty = { initialAnswer },
                                        ifSome = { (_, prevMessage) -> prevMessage },
                                    )

                                    (event to message)
                                }

                                is KbsStreamResponse.KbsChatResponse -> {
                                    val prevMessage = prev.fold(
                                        ifEmpty = { initialAnswer },
                                        ifSome = { (_, prevMessage) -> prevMessage },
                                    )

                                    val message = responseToMessage(response, messageId)
                                    val event = prevMessage.diff(message)

                                    (event to message)
                                }
                            }.some()
                        },
                        ifLeft = { errorResponse ->
                            handleError(errorResponse, messageId).bind()
                                .let { message ->
                                    (ConversationEvent.ErrorsUpdated(messageId, message.errors) to message).some()
                                }
                        },
                    )
                }
                .map { it.getOrNull() }
                .filterNotNull()
                .onEach { (_, message) -> latestMessage = message }
                .onCompletion {
                    latestMessage.let { message ->
                        messageService.updateAnswer(
                            messageId = messageId,
                            pending = false,
                            messageContent = message.content,
                            citations = message.citations.map { NewCitation(it.text, it.sourceId) },
                            context = message.context,
                            followUp = message.followUp,
                            userQuestion = message.userQuestion,
                            contextualizedQuestion = message.contextualizedQuestion,
                            tools = message.tools,
                            thinking = message.thinking,
                        ).bind().let { message ->
                            send(ConversationEvent.PendingUpdated(message.id, message, false))
                        }
                    }
                }
                .collect { (event, _) -> send(event) }
        }.catch { throwable ->
            emit(ConversationEvent.ErrorsUpdated(messageId, handleError(throwable, messageId).bind().errors))
        }.onCompletion { timer.stop() }
    }

    private suspend fun handleError(
        throwable: Throwable,
        messageId: MessageId
    ): ApplicationResult<Message> {
        logger.error(throwable) { "Error when receiving answer from KBS" }
        return handleError(
            MessageError(
                title = "Ukjent feil",
                description = "En ukjent feil oppsto når vi mottok svar fra språkmodellen."
            ),
            messageId
        )
    }

    private suspend fun handleError(
        errorResponse: KbsErrorResponse,
        messageId: MessageId
    ): ApplicationResult<Message> {
        val failedReceive = !errorResponse.title.lowercase().contains("flagget")
        return handleError(
            MessageError(
                title = errorResponse.title,
                description = errorResponse.detail,
            ),
            messageId,
            failedReceive,
        )
    }

    private suspend fun handleError(
        messageError: MessageError,
        messageId: MessageId,
        failedReceive: Boolean = true,
    ): ApplicationResult<Message> {
        if (failedReceive) {
            MetricRegister.answerFailedReceive.inc()
        }

        return messageService.updateMessageError(
            messageId = messageId,
            pending = false,
            errors = listOf(messageError),
        )
    }
}

fun responseToMessage(
    response: KbsStreamResponse.KbsChatResponse,
    messageId: MessageId,
): Message {
    val citations =
        response.citations.flatMap { (sourceId, citationTexts) ->
            citationTexts.map { NewCitation(it, sourceId) }
        }

    val context =
        response.context.map { (sourceId, ctx) ->
            sourceId to ctx.toModel(sourceId)
        }.toMap()

    val tools =
        response.tools.map { (name, arguments, success) ->
            Tool(name, arguments, success)
        }

    return Message.answerFrom(
        messageId = messageId,
        content = response.answer,
        citations = citations,
        context = context,
        followUp = response.followUp,
        userQuestion = null,
        contextualizedQuestion = null,
        tools = tools,
        thinking = response.thinking,
    )
}

