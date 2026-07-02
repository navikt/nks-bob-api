package no.nav.nks_ai.api.core.message

import arrow.core.Some
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.some
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.FeatureToggles
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.api.vaskemaskin.VaskemaskinClient

private val logger = KotlinLogging.logger { }

class MessageService(
    private val vaskemaskinClient: VaskemaskinClient,
    private val featureToggles: FeatureToggles,
    private val backgroundScope: CoroutineScope,
) {
    suspend fun addQuestion(
        conversationId: ConversationId,
        navIdent: NavIdent,
        messageContent: String,
    ): ApplicationResult<Message> = either {
        MetricRegister.questionsCreated.inc()
        val content = if (featureToggles.isVaskemaskinDetectionEnabled()) {
            // fire-and-forget: detect pii in background to gather metrics without blocking the request
            backgroundScope.launch { vaskemaskinClient.detect(messageContent) }
            messageContent
        } else if (featureToggles.isVaskemaskinAnonymizationEnabled()) {
            vaskemaskinClient.anonymize(messageContent).bind()
        } else {
            messageContent
        }
        MessageRepo.addMessage(
            conversationId = conversationId,
            messageContent = content,
            createdBy = navIdent.hash,
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            context = emptyList(),
            citations = emptyList(),
            pending = false,
        ).bind()
    }

    suspend fun addAnswer(
        conversationId: ConversationId,
        messageContent: String,
        citations: List<NewCitation>,
        context: List<Context>,
    ): ApplicationResult<Message> {
        MetricRegister.answersCreated.inc()
        return MessageRepo.addMessage(
            conversationId = conversationId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
            citations = citations.map(Citation::fromNewCitation),
            pending = true,
        )
    }

    suspend fun addEmptyAnswer(conversationId: ConversationId): ApplicationResult<Message> =
        addAnswer(
            conversationId = conversationId,
            messageContent = "",
            citations = emptyList(),
            context = emptyList()
        )

    suspend fun updateAnswer(
        messageId: MessageId,
        messageContent: String,
        citations: List<NewCitation>,
        context: Map<String, Context>,
        followUp: List<String>,
        pending: Boolean,
        userQuestion: String?,
        contextualizedQuestion: String?,
        tools: List<Tool>,
        thinking: List<String>,
        model: String?,
    ) =
        MessageRepo.updateMessage(
            messageId = messageId,
            messageContent = messageContent,
            createdBy = "Bob",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            context = context,
            citations = citations.map(Citation::fromNewCitation),
            followUp = followUp,
            pending = pending,
            userQuestion = userQuestion,
            contextualizedQuestion = contextualizedQuestion,
            tools = emptyList(),
            toolsV2 = tools,
            thinking = thinking,
            model = model,
        )

    suspend fun markStarredMessageUploaded(messageId: MessageId) =
        MessageRepo.markStarredMessageUploaded(messageId)

    suspend fun getStarredMessagesNotUploaded(): ApplicationResult<List<Message>> =
        MessageRepo.getStarredMessagesNotUploaded()

    suspend fun updateMessageError(
        messageId: MessageId,
        errors: List<MessageError>,
        pending: Boolean = false
    ) =
        MessageRepo.patchMessage(
            messageId = messageId,
            errors = Some(errors),
            pending = Some(pending),
        )

    suspend fun getMessage(messageId: MessageId) =
        MessageRepo.getMessage(messageId)

    suspend fun getMessage(messageId: MessageId, navIdent: NavIdent): ApplicationResult<Message> =
        either {
            ensure(isOwnedBy(messageId, navIdent).bind()) { ApplicationError.MissingAccess() }
            getMessage(messageId).bind()
        }

    suspend fun isOwnedBy(messageId: MessageId, navIdent: NavIdent): ApplicationResult<Boolean> = either {
        val ownedBy = MessageRepo.getOwner(messageId).bind()
        navIdent.isVerified(ownedBy)
    }

    suspend fun updateMessage(messageId: MessageId, navIdent: NavIdent, message: UpdateMessage): ApplicationResult<Message> =
        either {
            ensure(isOwnedBy(messageId, navIdent).bind()) { ApplicationError.MissingAccess() }
            MessageRepo.patchMessage(
                messageId = messageId,
                starred = message.starred.some(),
            ).bind()
        }

    suspend fun deleteOldMessages(deleteBefore: LocalDateTime): ApplicationResult<Int> = either {
        val deletedCount = MessageRepo.deleteMessagesCreatedBefore(deleteBefore).bind()
        logger.info { "$deletedCount messages older than $deleteBefore deleted" }
        deletedCount
    }
}
