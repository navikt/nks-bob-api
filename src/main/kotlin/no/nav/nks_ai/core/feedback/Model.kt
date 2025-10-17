package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
import io.ktor.server.application.ApplicationCall
import java.util.*
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

fun ApplicationCall.feedbackId(name: String = "id"): ApplicationResult<FeedbackId> = either {
    parameters[name]?.toUUID()?.toFeedbackId()
        ?: raise(ApplicationError.MissingFeedbackId())
}

enum class FeedbackFilter(val value: String) {
    Unresolved("nye"),
    Resolved("ferdigstilte"),
    NotRelevant("ikke-relevante"),
    SomewhatImportant("litt-viktige"),
    Important("viktige"),
    VeryImportant("særskilt-viktige"),
    UserError("brukerfeil"),
    AiError("ki-feil"),
    InaccurateAnswer("hele-deler-av-svaret-er-feil"),
    MissingDetails("mangler-vesentlige-detaljer"),
    UnexpectedArticle("benytter-ikke-forventede-artikler"),
    WrongContext("forholder-seg-ikke-til-kontekst"),
    MixingBenefits("blander-ytelser"),
    CitationNotFound("finner-ikke-sitatet-i-artikkelen"),
    MissingSources("mangler-kilder"),
    Other("annet");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }

        val validValues = entries.toTypedArray().asList().joinToString(", ") { it.value }

        fun fromFilterValue(value: String): ApplicationResult<FeedbackFilter> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing filter value $value. Valid values: $validValues"))
        }

        fun getResolvedImportance(filter: FeedbackFilter): ApplicationResult<ResolvedImportance> = either {
            when (filter) {
                NotRelevant -> ResolvedImportance.NotRelevant
                SomewhatImportant -> ResolvedImportance.SomewhatImportant
                Important -> ResolvedImportance.Important
                VeryImportant -> ResolvedImportance.VeryImportant
                else -> raise(
                    ApplicationError.InvalidInput(
                        "Invalid input",
                        "Supplied value ${filter.value} is not a resolved importance value."
                    )
                )
            }
        }

        fun getOptionText(filter: FeedbackFilter): ApplicationResult<String> = either {
            when (filter) {
                InaccurateAnswer -> "Hele-/deler av svaret er feil"
                MissingDetails -> "Mangler vesentlige detaljer"
                UnexpectedArticle -> "Benytter ikke forventede artikler"
                WrongContext -> "Forholder seg ikke til kontekst"
                MixingBenefits -> "Blander ytelser"
                CitationNotFound -> "Finner ikke sitatet i artikkelen"
                MissingSources -> "Mangler kilder"
                Other -> "Annet"
                else -> raise(
                    ApplicationError.InvalidInput(
                        "Invalid input",
                        "Supplied value ${filter.value} is not an option value."
                    )
                )
            }
        }
    }
}

class ResolvedImportanceSerializer : KSerializer<ResolvedImportance> {
    override fun serialize(
        encoder: Encoder,
        resolvedImportance: ResolvedImportance
    ) {
        encoder.encodeString(resolvedImportance.value)
    }

    override fun deserialize(decoder: Decoder): ResolvedImportance {
        val value = decoder.decodeString()
        return ResolvedImportance.fromImportanceValue(value).getOrNull()
            ?: throw InvalidInputException("Error parsing importance value $value. Valid values: ${ResolvedImportance.validValues}")
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("ResolvedImportance", PrimitiveKind.STRING)
}

@Serializable(ResolvedImportanceSerializer::class)
enum class ResolvedImportance(val value: String) {
    NotRelevant("ikke-relevant"),
    SomewhatImportant("lite-viktig"),
    Important("viktig"),
    VeryImportant("særskilt-viktig");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }

        val validValues = entries.toTypedArray().asList().joinToString(", ") { it.value }

        fun fromImportanceValue(value: String): ApplicationResult<ResolvedImportance> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing importance value $value. Valid values: $validValues"))
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
    UserError("brukerfeil"),
    AiError("ki-feil");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }
        val validValues = entries.toTypedArray().asList().joinToString(", ") { it.value }

        fun fromCategoryValue(value: String): ApplicationResult<ResolvedCategory> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing category value $value. Valid values: $validValues"))
        }
    }
}

@Serializable
data class Feedback(
    val id: FeedbackId,
    val messageId: MessageId?,
    val conversationId: ConversationId?,
    val createdAt: LocalDateTime,
    val options: List<String>,
    val comment: String?,
    val resolved: Boolean,
    val resolvedImportance: ResolvedImportance?,
    val resolvedCategory: ResolvedCategory?,
    val resolvedNote: String?,
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
    val resolvedImportance: ResolvedImportance?,
    val resolvedCategory: ResolvedCategory?,
    val resolvedNote: String?,
)
