package no.nav.nks_ai.feedback

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.now
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

object Feedbacks : UUIDTable() {
    val liked = bool("liked")
    val createdAt = datetime("created_at")
}

class FeedbackDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<FeedbackDAO>(Feedbacks)

    var liked by Feedbacks.liked
    var createdAt by Feedbacks.createdAt
}

fun FeedbackDAO.toModel() = Feedback(
    id = id.toString(),
    liked = liked,
)

@Serializable
data class Feedback(
    val id: String,
    val liked: Boolean
)

@Serializable
data class NewFeedback(
    val liked: Boolean,
)

class FeedbackRepo {
    suspend fun addFeedback(newFeedback: NewFeedback) =
        suspendTransaction {
            FeedbackDAO.new {
                this.liked = newFeedback.liked
                this.createdAt = LocalDateTime.now()
            }
        }

    // TODO referential constraints
    suspend fun removeFeedback(feedbackID: UUID) =
        suspendTransaction {
            FeedbackDAO.findById(feedbackID)?.delete()
        }
}