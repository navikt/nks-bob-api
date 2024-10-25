package no.nav.nks_ai.core.conversation

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.core.message.NewMessage
import java.util.UUID

object ConversationIdSerializer : KSerializer<ConversationId> {
    override fun deserialize(decoder: Decoder): ConversationId {
        return UUID.fromString(decoder.decodeString()).toConversationId()
    }

    override val descriptor: SerialDescriptor
        get() = TODO("Not yet implemented")

    override fun serialize(
        encoder: Encoder,
        conversationId: ConversationId
    ) {
        encoder.encodeString(conversationId.value.toString())
    }
}

@Serializable(ConversationIdSerializer::class)
@JvmInline
value class ConversationId(@Contextual val value: UUID)

fun UUID.toConversationId() = ConversationId(this)

fun ApplicationCall.conversationId(name: String = "id"): ConversationId? =
    ConversationId(UUID.fromString(this.parameters[name]))


@Serializable
data class Conversation(
    val id: ConversationId,
    val title: String,
    val createdAt: LocalDateTime,
    val owner: String,
)

@Serializable
data class NewConversation(
    val title: String,
    val initialMessage: NewMessage?,
)

@Serializable
data class UpdateConversation(
    val title: String,
)

sealed class ConversationError(
    override val code: HttpStatusCode,
    override val message: String,
    override val description: String
) : ApplicationError(code, message, description) {
    class ConversationNotFound(id: ConversationId) : ConversationError(
        HttpStatusCode.NotFound,
        "Conversation not found",
        "Conversation with id $id not found"
    )
}