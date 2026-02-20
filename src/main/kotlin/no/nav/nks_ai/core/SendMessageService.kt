package no.nav.nks_ai.core

import arrow.core.Either
import arrow.core.left
import arrow.core.none
import arrow.core.raise.either
import arrow.core.right
import arrow.core.some
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.conversation.streaming.ConversationEvent
import no.nav.nks_ai.core.conversation.streaming.diff
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageError
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.MessageType
import no.nav.nks_ai.core.message.NewCitation
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.core.message.answerFrom
import no.nav.nks_ai.core.user.NavIdent
import no.nav.nks_ai.kbs.KbsChatMessage
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.kbs.KbsErrorResponse
import no.nav.nks_ai.kbs.KbsStreamResponse
import no.nav.nks_ai.kbs.fromMessage
import no.nav.nks_ai.kbs.toModel
import no.nav.nks_ai.kbs.toNewCitation
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
            ).runningFold(none<Message>()) { prev, response ->
                response.map {
                    when (it) {
                        is KbsStreamResponse.StatusUpdateResponse -> {
                            send(ConversationEvent.StatusUpdate(messageId, it.text))
                        }

                        is KbsStreamResponse.KbsChatResponse -> {
                            val message = responseToMessage(it, messageId)
                            prev.onSome { prevMessage ->
                                val event = prevMessage.diff(message)
                                send(event)
                            }

                            prev.onNone {
                                send(ConversationEvent.NewMessage(messageId, message))
                            }

                            return@runningFold message.some()
                        }
                    }
                }.onLeft { errorResponse ->
                    handleError(errorResponse, messageId)
                        .onRight { message ->
                            send(
                                ConversationEvent.ErrorsUpdated(messageId, message.errors)
                            )
                        }
                }

                return@runningFold none()
            }
                .conflate()
                .collectLatest { latest ->
                    delay(3.seconds)
                    latest.onSome { message ->
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
                        ).map {
                            send(
                                ConversationEvent.PendingUpdated(
                                    id = message.id,
                                    message = it,
                                    pending = false,
                                )
                            )
                        }.onLeft { error ->
                            handleError(error, messageId)
                                .onRight { message ->
                                    send(
                                        ConversationEvent.ErrorsUpdated(messageId, message.errors)
                                    )
                                }
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
        error: ApplicationError,
        messageId: MessageId
    ): ApplicationResult<Message> {
        return handleError(
            MessageError(
                title = error.message,
                description = error.description
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

    suspend fun sendMessageStream(
        message: NewMessage,
        conversationId: ConversationId,
        navIdent: NavIdent,
    ): ApplicationResult<Flow<Message>> = either {
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
                .map { responseToMessage(it, initialAnswer.id) }
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
                    tools = message.tools,
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
    ): ApplicationResult<Message> {
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
    response: KbsStreamResponse.KbsChatResponse,
    messageId: MessageId,
): Message {
    val answerContent = response.answer.text
    val citations = response.answer.citations.map { it.toNewCitation() }
    val context = response.context.map { it.toModel() }

    return Message.answerFrom(
        messageId = messageId,
        content = answerContent,
        citations = citations,
        context = context,
        followUp = response.followUp,
        userQuestion = response.question.user,
        contextualizedQuestion = response.question.contextualized,
        tools = response.tools,
    )
}

private fun responseToMessage(
    kbsResponse: Either<KbsErrorResponse, KbsStreamResponse>,
    messageId: MessageId,
): Either<MessageError, Message> = either {
    val response = kbsResponse
        .mapLeft { error ->
            MessageError(
                title = error.title,
                description = error.detail,
            )
        }.bind()

    when (response) {
        is KbsStreamResponse.StatusUpdateResponse -> {
            // TODO support for status update
            raise(MessageError(title = "Status update", description = response.text))
        }

        is KbsStreamResponse.KbsChatResponse -> {
            val answerContent = response.answer.text
            val citations = response.answer.citations.map { it.toNewCitation() }
            val context = response.context.map { it.toModel() }

            Message.answerFrom(
                messageId = messageId,
                content = answerContent,
                citations = citations,
                context = context,
                followUp = response.followUp,
                userQuestion = response.question.user,
                contextualizedQuestion = response.question.contextualized,
                tools = response.tools,
            )
        }
    }
}
