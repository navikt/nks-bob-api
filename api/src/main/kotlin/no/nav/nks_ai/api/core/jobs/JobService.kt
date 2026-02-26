package no.nav.nks_ai.api.core.jobs

import arrow.core.raise.either
import arrow.core.separateEither
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Config
import no.nav.nks_ai.api.core.MarkMessageStarredService
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.shared.DeleteOldConversationsSummary
import no.nav.nks_ai.shared.UploadStarredMessagesSummary
import kotlin.time.Clock

interface JobService {
    suspend fun deleteOldConversations(): ApplicationResult<DeleteOldConversationsSummary>

    suspend fun uploadStarredMessages(): ApplicationResult<UploadStarredMessagesSummary>
}

fun jobService(
    messageService: MessageService,
    conversationService: ConversationService,
    markMessageStarredService: MarkMessageStarredService,
) = object : JobService {
    override suspend fun deleteOldConversations(): ApplicationResult<DeleteOldConversationsSummary> = either {
        val deleteBefore = Clock.System.now()
            .minus(Config.conversationsMaxAge)
            .toLocalDateTime(TimeZone.currentSystemDefault())

        val deletedMessages = messageService.deleteOldMessages(deleteBefore)
        val deletedConversations = conversationService.deleteOldConversations(deleteBefore)

        DeleteOldConversationsSummary(
            deletedMessages = deletedMessages.bind(),
            deletedConversations = deletedConversations.bind(),
        )
    }

    override suspend fun uploadStarredMessages(): ApplicationResult<UploadStarredMessagesSummary> = either {
        val messages: List<MessageId> = messageService.getStarredMessagesNotUploaded().bind().map { it.id }
        logger.info { "Found ${messages.size} starred messages" }

        val (errors, uploadedMessages) = messages.map { messageId ->
            markMessageStarredService.markStarred(messageId)
        }.separateEither()

        if (uploadedMessages.isNotEmpty()) {
            logger.info { "Uploaded ${uploadedMessages.size} starred messages" }
        }

        if (errors.isNotEmpty()) {
            val errorDetails = errors.map { "${it.message}: ${it.description}" }
                .distinct().joinToString(", ")

            logger.error { "Error when uploading ${errors.size} starred messages: $errorDetails" }
        }

        UploadStarredMessagesSummary(
            uploadedMessages = uploadedMessages.size,
            errors = errors.map { "${it.message}: ${it.description}" },
        )
    }
}