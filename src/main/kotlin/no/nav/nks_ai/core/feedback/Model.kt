package no.nav.nks_ai.core.feedback

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.nks_ai.app.toUUID
import no.nav.nks_ai.core.message.MessageId
import java.util.UUID

object FeedbackIdSerializer : KSerializer<FeedbackId> {
    override fun deserialize(decoder: Decoder): FeedbackId {
        return decoder.decodeString().toUUID().toFeedbackId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("FeedbackId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        feedbackId: FeedbackId
    ) {
        encoder.encodeString(feedbackId.value.toString())
    }
}

@Serializable(FeedbackIdSerializer::class)
@JvmInline
value class FeedbackId(@Contextual val value: UUID)

fun UUID.toFeedbackId() = FeedbackId(this)

fun ApplicationCall.feedbackId(name: String = "id"): FeedbackId? =
    this.parameters[name]?.toUUID()?.toFeedbackId()

enum class FeedbackFilter {
    Unresolved,
    Resolved,
    Important,
    VeryImportant
}

@Serializable
data class Feedback(
    val id: FeedbackId,
    val messageId: MessageId,
    val createdAt: LocalDateTime,
    val options: List<String>,
    val comment: String?,
    val resolved: Boolean,
)

@Serializable
data class CreateFeedback(
    val options: List<String>,
    val comment: String?,
)

@Serializable
data class UpdateFeedback(
    val options: List<String>,
    val comment: String?,
    val resolved: Boolean,
)