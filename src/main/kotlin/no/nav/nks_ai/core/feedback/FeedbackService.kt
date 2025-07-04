package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
import arrow.core.raise.ensure
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.Page
import no.nav.nks_ai.app.Pagination
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.MessageType
import no.nav.nks_ai.core.user.NavIdent

interface FeedbackService {
    suspend fun getFeedback(feedbackId: FeedbackId): ApplicationResult<Feedback>

    suspend fun getAllFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>>

    suspend fun getFilteredFeedbacks(filter: FeedbackFilter?, pagination: Pagination): ApplicationResult<Page<Feedback>>

    suspend fun getFeedbacksForMessage(messageId: MessageId, navIdent: NavIdent): ApplicationResult<List<Feedback>>

    suspend fun addFeedback(
        messageId: MessageId,
        navIdent: NavIdent,
        feedback: CreateFeedback
    ): ApplicationResult<Feedback>

    suspend fun updateFeedback(feedbackId: FeedbackId, feedback: UpdateFeedback): ApplicationResult<Feedback>

    suspend fun deleteFeedback(feedbackId: FeedbackId): ApplicationResult<Unit>
}

fun feedbackService(messageService: MessageService) = object : FeedbackService {
    override suspend fun getFeedback(feedbackId: FeedbackId): ApplicationResult<Feedback> =
        FeedbackRepo.getFeedbackById(feedbackId)

    override suspend fun getAllFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        FeedbackRepo.getFeedbacks(pagination)

    override suspend fun getFilteredFeedbacks(
        filter: FeedbackFilter?,
        pagination: Pagination
    ): ApplicationResult<Page<Feedback>> =
        when (filter) {
            FeedbackFilter.Unresolved -> FeedbackRepo.getUnresolvedFeedbacks(pagination)
            FeedbackFilter.Resolved -> FeedbackRepo.getResolvedFeedbacks(pagination)
            FeedbackFilter.NotRelevant -> FeedbackRepo.getNotRelevantFeedbacks(pagination)
            FeedbackFilter.SomewhatImportant -> FeedbackRepo.getSomewhatImportantFeedbacks(pagination)
            FeedbackFilter.Important -> FeedbackRepo.getImportantFeedbacks(pagination)
            FeedbackFilter.VeryImportant -> FeedbackRepo.getVeryImportantFeedbacks(pagination)
            FeedbackFilter.UserError -> FeedbackRepo.getUserErrorFeedbacks(pagination)
            FeedbackFilter.AiError -> FeedbackRepo.getAiErrorFeedbacks(pagination)
            null -> getAllFeedbacks(pagination)
        }

    override suspend fun getFeedbacksForMessage(
        messageId: MessageId,
        navIdent: NavIdent
    ): ApplicationResult<List<Feedback>> = either {
        messageService.getMessage(messageId, navIdent).bind() // ensures owner
        FeedbackRepo.getFeedbacksByMessageId(messageId).bind()
    }

    override suspend fun addFeedback(
        messageId: MessageId,
        navIdent: NavIdent,
        feedback: CreateFeedback
    ): ApplicationResult<Feedback> = either {
        val message = messageService.getMessage(messageId, navIdent).bind()
        ensure(message.messageType == MessageType.Answer) {
            ApplicationError.BadRequest("Cannot add feedback to a message which is not an answer")
        }

        MetricRegister.trackFeedback(
            options = feedback.options,
            hasComment = feedback.comment != null
        )

        FeedbackRepo.addFeedback(
            messageId = messageId,
            options = feedback.options,
            comment = feedback.comment,
        ).bind()
    }

    override suspend fun updateFeedback(
        feedbackId: FeedbackId,
        feedback: UpdateFeedback,
    ): ApplicationResult<Feedback> = either {
        MetricRegister.trackFeedbackResolved(
            feedback.resolved,
            feedback.resolvedImportance,
            feedback.resolvedCategory,
        )

        FeedbackRepo.updateFeedback(
            feedbackId = feedbackId,
            options = feedback.options,
            comment = feedback.comment,
            resolved = feedback.resolved,
            resolvedImportance = feedback.resolvedImportance,
            resolvedCategory = feedback.resolvedCategory,
            resolvedNote = feedback.resolvedNote,
        ).bind()
    }

    override suspend fun deleteFeedback(feedbackId: FeedbackId): ApplicationResult<Unit> =
        FeedbackRepo.deleteFeedback(feedbackId)
}

