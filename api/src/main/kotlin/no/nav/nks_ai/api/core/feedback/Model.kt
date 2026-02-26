package no.nav.nks_ai.api.core.feedback

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
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.InvalidInputException
import no.nav.nks_ai.api.app.toUUID
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.message.MessageId

object FeedbackIdSerializer : KSerializer<FeedbackId> {
    override fun deserialize(decoder: Decoder): FeedbackId {
        return decoder.decodeString().toUUID().toFeedbackId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("FeedbackId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: FeedbackId
    ) {
        encoder.encodeString(value.value.toString())
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
    DateExpired("dato-utgatt"),
    InaccurateAnswer("hele-deler-av-svaret-er-feil"),
    MissingDetails("mangler-vesentlige-detaljer"),
    UnexpectedArticle("benytter-ikke-forventede-artikler"),
    WrongContext("forholder-seg-ikke-til-kontekst"),
    MixingBenefits("blander-ytelser"),
    CitationNotFound("finner-ikke-sitatet-i-artikkelen"),
    MissingSources("mangler-kilder"),
    Other("annet"),
    Arbeid("arbeid"),
    Helse("helse"),
    Familie("familie"),
    Pleiepenger("pleiepenger"),
    Gjeldsveiledning("gjeldsveiledning"),
    SosialeTjenester("sosiale-tjenester"),
    Pensjon("pensjon"),
    Uforetrygd("uforetrygd"),
    Arbeidsgiver("arbeidsgiver"),
    Internasjonalt("internasjonalt"),
    Fellesrutinene("fellesrutinene"),
    Inactive("inaktive"),
    Active("aktive");

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

        fun getDomain(filter: FeedbackFilter): ApplicationResult<Domain> = either {
            when (filter) {
                Arbeid -> Domain.Arbeid
                Helse -> Domain.Helse
                Familie -> Domain.Familie
                Pleiepenger -> Domain.Pleiepenger
                Gjeldsveiledning -> Domain.Gjeldsveiledning
                SosialeTjenester -> Domain.SosialeTjenester
                Pensjon -> Domain.Pensjon
                Uforetrygd -> Domain.Uforetrygd
                Arbeidsgiver -> Domain.Arbeidsgiver
                Internasjonalt -> Domain.Internasjonalt
                Fellesrutinene -> Domain.Fellesrutinene
                else -> raise(
                    ApplicationError.InvalidInput(
                        "Invalid input",
                        "Supplied value ${filter.value} is not a domain value."
                    )
                )
            }
        }
    }
}

class ResolvedImportanceSerializer : KSerializer<ResolvedImportance> {
    override fun serialize(
        encoder: Encoder,
        value: ResolvedImportance
    ) {
        encoder.encodeString(value.value)
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
        value: ResolvedCategory
    ) {
        encoder.encodeString(value.value)
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
    AiError("ki-feil"),
    DateExpired("dato-utgatt");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }
        val validValues = entries.toTypedArray().asList().joinToString(", ") { it.value }

        fun fromCategoryValue(value: String): ApplicationResult<ResolvedCategory> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing category value $value. Valid values: $validValues"))
        }
    }
}

class DomainSerializer : KSerializer<Domain> {
    override fun serialize(encoder: Encoder, value: Domain) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): Domain {
        val value = decoder.decodeString()
        return Domain.fromValue(value).getOrNull()
            ?: throw InvalidInputException("Error parsing domain value $value. Valid values: ${Domain.validValues}")
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("Domain", PrimitiveKind.STRING)
}

@Serializable(DomainSerializer::class)
enum class Domain(val value: String) {
    Arbeid("arbeid"),
    Helse("helse"),
    Familie("familie"),
    Pleiepenger("pleiepenger"),
    Gjeldsveiledning("gjeldsveiledning"),
    SosialeTjenester("sosiale-tjenester"),
    Pensjon("pensjon"),
    Uforetrygd("uforetrygd"),
    Arbeidsgiver("arbeidsgiver"),
    Internasjonalt("internasjonalt"),
    Fellesrutinene("fellesrutinene");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }
        val validValues = entries.toTypedArray().asList().joinToString(", ") { it.value }

        fun fromValue(value: String): ApplicationResult<Domain> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing domain value $value. Valid values: ${validValues}"))
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
    val domain: Domain?
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
    val domain: Domain?,
)
