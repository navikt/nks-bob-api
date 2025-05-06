package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
import no.nav.nks_ai.core.message.MessageDAO
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.Messages
import no.nav.nks_ai.core.message.toMessageId
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

internal object Feedbacks : UUIDTable("feedbacks") {
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val message = reference("message", Messages)
    val options = array<String>("options")
    val comment = text("comment", eagerLoading = true).nullable().clientDefault { null }
    val resolved = bool("resolved").clientDefault { false }
}

internal class FeedbackDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<FeedbackDAO>(Feedbacks)

    var createdAt by Feedbacks.createdAt
    var message by MessageDAO.Companion referencedOn Feedbacks.message
    var options by Feedbacks.options
    var comment by Feedbacks.comment
    var resolved by Feedbacks.resolved
}

internal fun FeedbackDAO.toModel() = Feedback(
    id = id.value.toFeedbackId(),
    createdAt = createdAt,
    messageId = message.id.value.toMessageId(),
    options = options,
    comment = comment,
    resolved = resolved,
)

object FeedbackRepo {
    suspend fun getFeedbacks(): ApplicationResult<List<Feedback>> =
        suspendTransaction {
            either {
                FeedbackDAO.all().map(FeedbackDAO::toModel)
            }
        }

    suspend fun getUnresolvedFeedbacks(): ApplicationResult<List<Feedback>> =
        suspendTransaction {
            either {
                FeedbackDAO.find {
                    Feedbacks.resolved eq false
                }.map(FeedbackDAO::toModel)
            }
        }

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
                }.map(FeedbackDAO::toModel)
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
    ): ApplicationResult<Feedback> =
        suspendTransaction {
            either {
                FeedbackDAO.findByIdAndUpdate(feedbackId.value) {
                    it.options = options
                    it.comment = comment
                    it.resolved = resolved
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