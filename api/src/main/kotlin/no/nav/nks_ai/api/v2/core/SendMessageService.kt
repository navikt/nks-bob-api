package no.nav.nks_ai.api.v2.core

import arrow.core.Some
import arrow.core.left
import arrow.core.none
import arrow.core.raise.context.bind
import arrow.core.raise.either
import arrow.core.some
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.runningReduce
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.message.Context
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageError
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.MessageType
import no.nav.nks_ai.api.core.message.NewCitation
import no.nav.nks_ai.api.core.message.answerFrom
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.api.kbs.KbsChatMessage
import no.nav.nks_ai.api.kbs.KbsErrorResponse
import no.nav.nks_ai.api.kbs.fromMessage
import no.nav.nks_ai.api.kbs.toModel
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEvent
import no.nav.nks_ai.api.v2.kbs.KbsClient
import no.nav.nks_ai.api.v2.kbs.KbsStreamResponse
import no.nav.nks_ai.api.v2.kbs.toModel
import kotlin.collections.map
import kotlin.time.Duration.Companion.seconds

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

            kbsClient.sendQuestionStream(
                question = question.content,
                messageHistory = history,
            )
                .runningFold(none<ConversationEvent>()) { prev, result ->
                    result.map { response ->
                        when (response) {
                            is KbsStreamResponse.KbsTokenChunkResponse -> {
                                return@runningFold ConversationEvent.ContentUpdated(messageId, response.chunk).some()
                            }

                            is KbsStreamResponse.StatusUpdateResponse -> {
                                return@runningFold ConversationEvent.StatusUpdate(messageId, response.text).some()
                            }

                            is KbsStreamResponse.KbsChatResponse -> {
                                responseToMessage(response, messageId).let { message ->
                                    prev.onNone {
                                        return@runningFold ConversationEvent.NewMessage(messageId, message).some()
                                    }
                                    prev.onSome {
                                        return@runningFold ConversationEvent.MessageUpdated(messageId, message)
                                            .some()
                                    }
                                }
                            }
                        }
                    }.mapLeft { errorResponse ->
                        handleError(errorResponse, messageId)
                            .map { message ->
                                return@runningFold ConversationEvent.ErrorsUpdated(messageId, message.errors).some()
                            }
                    }

                    return@runningFold none()
                }
                .map { it.getOrNull() }
                .filterNotNull()
                .onEach { event -> send(event) }
                .collectLatest { event ->
                    delay(1.seconds)
                    when (event) {
                        is ConversationEvent.NewMessage -> {
                            event.message.some()
                        }

                        is ConversationEvent.MessageUpdated -> {
                            event.message.some()
                        }

                        else -> {
                            none()
                        }
                    }.onSome { message ->
                        messageService.updateAnswer(
                            messageId = message.id,
                            messageContent = message.content,
                            citations = message.citations.map { NewCitation(it.text, it.sourceId) },
                            context = message.context,
                            followUp = message.followUp,
                            pending = false,
                            userQuestion = message.userQuestion,
                            contextualizedQuestion = message.contextualizedQuestion,
                            tools = message.tools,
                        ).bind().let { message ->
                            send(ConversationEvent.PendingUpdated(message.id, message, false))
                        }
                    }
                }
        }.catch { throwable ->
            handleError(throwable, messageId)
                .onRight { message ->
                    emit(
                        ConversationEvent.ErrorsUpdated(messageId, message.errors)
                    )
                }
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

private fun responseToMessage(
    response: KbsStreamResponse.KbsChatResponse,
    messageId: MessageId,
): Message {
    val answerContent = response.answer
    // TODO prepare for uuid
    val citations =
        response.citations.map { (sourceId, citationTexts) ->
            citationTexts.map { NewCitation(it, sourceId.toInt()) }
        }.flatten()

    // TODO source Id
    val context = response.context.map {(sourceId, ctx) -> ctx.toModel() }

    // TODO migration needed
    val tools = emptyList<String>()

    return Message.answerFrom(
        messageId = messageId,
        content = answerContent,
        citations = citations,
        context = context,
        followUp = response.followUp,
        userQuestion = "",
        contextualizedQuestion = "",
        tools = tools,
    )
}

