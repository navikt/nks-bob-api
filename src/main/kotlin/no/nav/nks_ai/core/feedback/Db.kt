package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.BaseEntity
import no.nav.nks_ai.app.BaseEntityClass
import no.nav.nks_ai.app.BaseTable
import no.nav.nks_ai.app.Page
import no.nav.nks_ai.app.Pagination
import no.nav.nks_ai.app.paginated
import no.nav.nks_ai.app.suspendTransaction
import no.nav.nks_ai.core.conversation.toConversationId
import no.nav.nks_ai.core.message.MessageDAO
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.Messages
import no.nav.nks_ai.core.message.toMessageId
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import java.util.UUID

internal object Feedbacks : BaseTable("feedbacks") {
    val message = reference("message", Messages)
    val options = array<String>("options")
    val comment = text("comment", eagerLoading = true).nullable().clientDefault { null }
    val resolved = bool("resolved").clientDefault { false }
    val resolvedImportance = enumeration<ResolvedImportance>("resolved_importance").nullable().clientDefault { null }
    val resolvedCategory = enumeration<ResolvedCategory>("resolved_category").nullable().clientDefault { null }
    val resolvedNote = text("resolved_note").nullable().clientDefault { null }
}

internal class FeedbackDAO(id: EntityID<UUID>) : BaseEntity(id, Feedbacks) {
    companion object : BaseEntityClass<FeedbackDAO>(Feedbacks)

    var message by MessageDAO.Companion referencedOn Feedbacks.message
    var options by Feedbacks.options
    var comment by Feedbacks.comment
    var resolved by Feedbacks.resolved
    var resolvedImportance by Feedbacks.resolvedImportance
    var resolvedCategory by Feedbacks.resolvedCategory
    var resolvedNote by Feedbacks.resolvedNote
}

internal fun FeedbackDAO.toModel() = Feedback(
    id = id.value.toFeedbackId(),
    createdAt = createdAt,
    messageId = message.id.value.toMessageId(),
    conversationId = message.conversation.id.value.toConversationId(),
    options = options,
    comment = comment,
    resolved = resolved,
    resolvedImportance = resolvedImportance,
    resolvedCategory = resolvedCategory,
    resolvedNote = resolvedNote,
)

object FeedbackRepo {
    suspend fun getFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        suspendTransaction {
            either {
                Page(
                    data = FeedbackDAO.all()
                        .paginated(pagination, Feedbacks)
                        .map(FeedbackDAO::toModel),
                    total = FeedbackDAO.all().count(),
                )
            }
        }

    private suspend fun getFilteredFeedbacks(
        pagination: Pagination,
        op: SqlExpressionBuilder.() -> Op<Boolean>
    ): ApplicationResult<Page<Feedback>> =
        suspendTransaction {
            either {
                Page(
                    data = FeedbackDAO.find(op)
                        .paginated(pagination, Feedbacks)
                        .map(FeedbackDAO::toModel),
                    total = FeedbackDAO.find(op).count()
                )
            }
        }

    suspend fun getUnresolvedFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolved eq false }

    suspend fun getResolvedFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolved eq true }

    suspend fun getNotRelevantFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolvedImportance eq ResolvedImportance.NotRelevant }

    suspend fun getSomewhatImportantFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolvedImportance eq ResolvedImportance.SomewhatImportant }

    suspend fun getImportantFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolvedImportance eq ResolvedImportance.Important }

    suspend fun getVeryImportantFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolvedImportance eq ResolvedImportance.VeryImportant }

    suspend fun getUserErrorFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolvedCategory eq ResolvedCategory.UserError }

    suspend fun getAiErrorFeedbacks(pagination: Pagination): ApplicationResult<Page<Feedback>> =
        getFilteredFeedbacks(pagination) { Feedbacks.resolvedCategory eq ResolvedCategory.AiError }

    suspend fun getFeedbackById(feedbackId: FeedbackId): ApplicationResult<Feedback> =
        suspendTransaction {
            either {
                FeedbackDAO.findById(feedbackId.value)
                    ?.let(FeedbackDAO::toModel)
                    ?: raise(ApplicationError.FeedbackNotFound(feedbackId))
            }
        }

    suspend fun getFeedbacksByMessageId(messageId: MessageId): ApplicationResult<List<Feedback>> =
        suspendTransaction {
            either {
                FeedbackDAO.find {
                    Feedbacks.message eq messageId.value
                }
                    .map(FeedbackDAO::toModel)
                    .sortedByDescending(Feedback::createdAt)
            }
        }

    suspend fun addFeedback(
        messageId: MessageId,
        options: List<String>,
        comment: String?
    ): ApplicationResult<Feedback> =
        suspendTransaction {
            either {
                val message = MessageDAO.Companion.findById(messageId.value)
                    ?: raise(ApplicationError.MessageNotFound(messageId))

                FeedbackDAO.new {
                    this.message = message
                    this.options = options
                    this.comment = comment
                }.let(FeedbackDAO::toModel)
            }
        }

    suspend fun updateFeedback(
        feedbackId: FeedbackId,
        options: List<String>,
        comment: String?,
        resolved: Boolean,
        resolvedImportance: ResolvedImportance?,
        resolvedCategory: ResolvedCategory?,
        resolvedNote: String?,
    ): ApplicationResult<Feedback> =
        suspendTransaction {
            either {
                FeedbackDAO.findByIdAndUpdate(feedbackId.value) {
                    it.options = options
                    it.comment = comment
                    it.resolved = resolved
                    it.resolvedImportance = resolvedImportance
                    it.resolvedCategory = resolvedCategory
                    it.resolvedNote = resolvedNote
                }
                    ?.let(FeedbackDAO::toModel)
                    ?: raise(ApplicationError.FeedbackNotFound(feedbackId))
            }
        }

    suspend fun deleteFeedback(feedbackId: FeedbackId): ApplicationResult<Unit> =
        suspendTransaction {
            either {
                FeedbackDAO.findById(feedbackId.value)?.delete()
                    ?: raise(ApplicationError.FeedbackNotFound(feedbackId))
            }
        }
}