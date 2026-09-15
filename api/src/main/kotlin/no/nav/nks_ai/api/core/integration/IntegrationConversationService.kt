package no.nav.nks_ai.api.core.integration

import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageError
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.api.v2.core.SendMessageService
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEvent
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEventBus
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}
private val navIdentPattern = Regex("[A-Za-z][0-9]{6}")

interface IntegrationConversationService {
    suspend fun createConversation(
        owner: NavIdent,
        conversation: NewConversation,
    ): ApplicationResult<Conversation>

    suspend fun createMessage(
        owner: NavIdent,
        conversationId: ConversationId,
        message: NewMessage,
    ): ApplicationResult<AcceptedIntegrationMessage>

    suspend fun shutdown()

    companion object {
        const val ROOT = "/api/v1/integrations/users/{navIdent}/conversations"

        fun create(
            conversationService: ConversationService,
            messageService: MessageService,
            sendMessageService: SendMessageService,
            conversationEventBus: ConversationEventBus,
            backgroundScope: CoroutineScope,
        ): IntegrationConversationService = DefaultIntegrationConversationService(
            conversationService, messageService, sendMessageService, conversationEventBus, backgroundScope,
        )
    }
}

private class DefaultIntegrationConversationService(
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val sendMessageService: SendMessageService,
    private val conversationEventBus: ConversationEventBus,
    backgroundScope: CoroutineScope,
) : IntegrationConversationService {
    private val lifecycleMutex = Mutex()
    private val serviceJob = SupervisorJob(checkNotNull(backgroundScope.coroutineContext[Job]) {
        "Integration processing requires an application-owned Job"
    })
    private val scope = CoroutineScope(
        backgroundScope.coroutineContext + serviceJob + Dispatchers.IO +
            CoroutineExceptionHandler { _, cause ->
                logger.error { "Unhandled integration processing failure (${cause::class.simpleName})" }
            }
    )

    override suspend fun createConversation(
        owner: NavIdent,
        conversation: NewConversation,
    ): ApplicationResult<Conversation> = either {
        validateOwner(owner).bind()
        ensure(serviceJob.isActive) { unavailable() }
        ensure(conversation.title.isNotBlank() && conversation.title.length <= 255) {
            ApplicationError.BadRequest("Title must contain between 1 and 255 characters")
        }
        val initialMessage = conversation.initialMessage
        if (initialMessage == null) {
            conversationService.addConversation(owner, conversation).bind()
        } else {
            validateMessage(initialMessage).bind()
            val (created, question) = messageService.addConversationWithQuestion(
                owner, conversation.title, initialMessage.content,
            ).bind()
            startProcessing(owner, created.id, question).bind()
            created
        }
    }

    override suspend fun createMessage(
        owner: NavIdent,
        conversationId: ConversationId,
        message: NewMessage,
    ): ApplicationResult<AcceptedIntegrationMessage> = either {
        validateOwner(owner).bind()
        validateMessage(message).bind()
        ensure(serviceJob.isActive) { unavailable() }
        val question = messageService.addQuestion(conversationId, owner, message.content).bind()
        startProcessing(owner, conversationId, question).bind()
        AcceptedIntegrationMessage(conversationId, question.id)
    }

    override suspend fun shutdown() {
        lifecycleMutex.withLock { serviceJob.cancel() }
        serviceJob.join()
    }

    private suspend fun startProcessing(
        owner: NavIdent,
        conversationId: ConversationId,
        question: Message,
    ): ApplicationResult<Unit> {
        val accepted = lifecycleMutex.withLock {
            if (!serviceJob.isActive) {
                false
            } else {
                // Install cancellation cleanup before dispatch so shutdown cannot skip it.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    process(owner, conversationId, question)
                }
                true
            }
        }
        if (accepted) return Unit.right()

        recordFailure(conversationId, question.id)
        return ApplicationError.MessageProcessingNotStarted(conversationId, question.id).left()
    }

    private suspend fun process(owner: NavIdent, conversationId: ConversationId, question: Message) {
        var answerId: MessageId? = null
        flow {
            yield()
            sendMessageService.askQuestion(question, conversationId, owner) { answerId = it }.fold(
                ifLeft = { recordFailure(conversationId, answerId ?: question.id) },
                ifRight = { emitAll(it) },
            )
        }.catch { cause ->
            logger.error { "Integration message processing failed (${cause::class.simpleName})" }
            recordFailure(conversationId, answerId ?: question.id)
        }.onCompletion { cause ->
            if (cause is CancellationException) {
                withContext(NonCancellable) {
                    val recorded = withTimeoutOrNull(5_000.milliseconds) {
                        recordFailure(conversationId, answerId ?: question.id)
                        true
                    }
                    if (recorded == null) logger.error { "Timed out recording interrupted integration processing" }
                }
            }
        }.collect()
    }

    private suspend fun recordFailure(conversationId: ConversationId, messageId: MessageId) {
        messageService.updateMessageError(
            messageId,
            errors = listOf(MessageError(
                title = "Behandling avbrutt",
                description = "Meldingen er lagret, men svaret kunne ikke fullføres.",
            )),
            pending = false,
        ).fold(
            ifLeft = { logger.error { "Could not persist integration processing failure" } },
            ifRight = { message ->
                conversationEventBus.publish(conversationId, ConversationEvent.ErrorsUpdated(messageId, message.errors))
                conversationEventBus.publish(
                    conversationId,
                    ConversationEvent.PendingUpdated(messageId, message, false),
                )
            },
        )
    }

    private fun validateOwner(owner: NavIdent): ApplicationResult<Unit> = either {
        ensure(navIdentPattern.matches(owner.plaintext.value)) {
            ApplicationError.BadRequest("Invalid Nav-ident format")
        }
    }

    private fun validateMessage(message: NewMessage): ApplicationResult<Unit> = either {
        ensure(message.content.isNotBlank() && message.content.length <= 20_000) {
            ApplicationError.BadRequest("Message must contain between 1 and 20000 characters")
        }
    }

    private fun unavailable() =
        ApplicationError.InternalServerError("Service stopping", "Message processing is not available")
}
