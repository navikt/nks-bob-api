package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
import arrow.core.raise.ensure
import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.asCache
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.MetricRegister
import no.nav.nks_ai.app.eitherGet
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.MessageType
import no.nav.nks_ai.core.user.NavIdent

interface FeedbackService {
    suspend fun getFeedback(feedbackId: FeedbackId): ApplicationResult<Feedback>

    suspend fun getAllFeedbacks(): ApplicationResult<List<Feedback>>

    suspend fun getFilteredFeedbacks(filter: FeedbackFilter): ApplicationResult<List<Feedback>>

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
    private val cache = Caffeine.newBuilder().asCache<String, List<Feedback>>()
    private val ALL = "all"

    override suspend fun getFeedback(feedbackId: FeedbackId): ApplicationResult<Feedback> =
        FeedbackRepo.getFeedbackById(feedbackId)

    override suspend fun getAllFeedbacks(): ApplicationResult<List<Feedback>> =
        cache.eitherGet(ALL) {
            FeedbackRepo.getFeedbacks()
        }

    override suspend fun getFilteredFeedbacks(filter: FeedbackFilter): ApplicationResult<List<Feedback>> =
        cache.eitherGet(filter.toString()) {
            when (filter) {
                FeedbackFilter.Unresolved -> FeedbackRepo.getUnresolvedFeedbacks()
                FeedbackFilter.Resolved -> FeedbackRepo.getResolvedFeedbacks()
                FeedbackFilter.Important -> FeedbackRepo.getFeedbacks() // TODO
                FeedbackFilter.VeryImportant -> FeedbackRepo.getFeedbacks() // TODO
            }
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

        cache.invalidateAll()
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
    ): ApplicationResult<Feedback> {
        cache.invalidateAll()
        return FeedbackRepo.updateFeedback(
            feedbackId = feedbackId,
            options = feedback.options,
            comment = feedback.comment,
            resolved = feedback.resolved
        )
    }

    override suspend fun deleteFeedback(feedbackId: FeedbackId): ApplicationResult<Unit> =
        FeedbackRepo.deleteFeedback(feedbackId)
}

