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

object IgnoredWordsIdSerializer : KSerializer<IgnoredWordsId> {
    override fun deserialize(decoder: Decoder): IgnoredWordsId {
        return decoder.decodeString().toUUID().toIgnoredWordsId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("IgnoredWordsId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: IgnoredWordsId
    ) {
        encoder.encodeString(value.value.toString())
    }
}

@Serializable(IgnoredWordsIdSerializer::class)
@JvmInline
value class IgnoredWordsId(@Contextual val value: UUID)

fun UUID.toIgnoredWordsId() = IgnoredWordsId(this)

fun ApplicationCall.ignoredWordsId(name: String = "id"): ApplicationResult<IgnoredWordsId> = either {
    parameters[name]?.toUUID()?.toIgnoredWordsId()
        ?: raise(ApplicationError.MissingIgnoredWordsId())
}

@Serializable
data class IgnoredWord(
    val id: IgnoredWordsId,
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
    val count: Int
)