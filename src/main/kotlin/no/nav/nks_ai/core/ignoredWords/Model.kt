package no.nav.nks_ai.core.ignoredWords

import arrow.core.raise.either
import io.ktor.server.application.*
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
import no.nav.nks_ai.app.toUUID
import no.nav.nks_ai.core.conversation.ConversationId
import java.util.*

object IgnoredWordIdSerializer : KSerializer<IgnoredWordId> {
    override fun deserialize(decoder: Decoder): IgnoredWordId {
        return decoder.decodeString().toUUID().toIgnoredWordId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("IgnoredWordId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: IgnoredWordId
    ) {
        encoder.encodeString(value.value.toString())
    }
}

@Serializable(IgnoredWordIdSerializer::class)
@JvmInline
value class IgnoredWordId(@Contextual val value: UUID)

fun UUID.toIgnoredWordId() = IgnoredWordId(this)

fun ApplicationCall.ignoredWordId(name: String = "id"): ApplicationResult<IgnoredWordId> = either {
    parameters[name]?.toUUID()?.toIgnoredWordId()
        ?: raise(ApplicationError.MissingIgnoredWordsId())
}

@Serializable
data class IgnoredWord(
    val id: IgnoredWordId,
    val value: String,
    val validationType: String,
    val conversationId: ConversationId?
)

@Serializable
data class NewIgnoredWord(
    val value: String,
    val validationType: String,
    val conversationId: ConversationId?
)

@Serializable
data class IgnoredWordAggregation(
    val value: String,
    val validationType: String,
    val count: Int
)