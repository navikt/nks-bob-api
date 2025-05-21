package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
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
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.InvalidInputException
import no.nav.nks_ai.app.toUUID
import no.nav.nks_ai.core.conversation.ConversationId
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

enum class FeedbackFilter(val value: String) {
    Unresolved("nye"),
    Resolved("ferdigstilte"),
    NotRelevant("ikke-relevante"),
    SomewhatImportant("litt-viktige"),
    Important("viktige"),
    VeryImportant("særskilt-viktige");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }

        val validValues = entries.toTypedArray().asList().map { it.value }.joinToString(", ")

        fun fromFilterValue(value: String): ApplicationResult<FeedbackFilter> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing filter value $value. Valid values: $validValues"))
        }
    }
}

class ResolvedCategorySerializer : KSerializer<ResolvedCategory> {
    override fun serialize(
        encoder: Encoder,
        resolvedCategory: ResolvedCategory
    ) {
        encoder.encodeString(resolvedCategory.value)
    }

    override fun deserialize(decoder: Decoder): ResolvedCategory {
        val value = decoder.decodeString()
        return ResolvedCategory.fromCategoryValue(value).getOrNull()
            ?: throw InvalidInputException("Error parsing category value $value. Valid values: ${ResolvedCategory.validValues}")
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("ResolvedCategory", PrimitiveKind.STRING)
}

@Serializable(ResolvedCategorySerializer::class)
enum class ResolvedCategory(val value: String) {
    NotRelevant("ikke-relevant"),
    SomewhatImportant("litt-viktig"),
    Important("viktig"),
    VeryImportant("særskilt-viktig");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }

        val validValues = entries.toTypedArray().asList().map { it.value }.joinToString(", ")

        fun fromCategoryValue(value: String): ApplicationResult<ResolvedCategory> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing category value $value. Valid values: $validValues"))
        }
    }
}

@Serializable
data class Feedback(
    val id: FeedbackId,
    val messageId: MessageId,
    val conversationId: ConversationId,
    val createdAt: LocalDateTime,
    val options: List<String>,
    val comment: String?,
    val resolved: Boolean,
    val resolvedCategory: ResolvedCategory?,
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
    val resolvedCategory: String?,
)