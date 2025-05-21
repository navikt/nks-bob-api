package no.nav.nks_ai.core.conversation

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
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.NewMessage
import java.util.UUID

object ConversationIdSerializer : KSerializer<ConversationId> {
    override fun deserialize(decoder: Decoder): ConversationId {
        return decoder.decodeString().toUUID().toConversationId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("ConversationId", PrimitiveKind.STRING)

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
    this.parameters[name]?.toUUID()?.toConversationId()

@Serializable
data class Conversation(
    val id: ConversationId,
    val title: String,
    val createdAt: LocalDateTime,
)

@Serializable
data class ConversationSummary(
    val id: ConversationId,
    val title: String,
    val createdAt: LocalDateTime,
    val messages: List<Message>,
) {
    val summary: String = messages.map { message ->
        val from = when (message.messageRole) {
            MessageRole.AI -> "Bob"
            MessageRole.Human -> "Bruker"
        }

        val content = when (message.content.isBlank()) {
            true -> "<tomt svar>"
            false -> message.content
        }

        "${from}:\n${content}\n"
    }.joinToString("\n")

    companion object {
        fun from(conversation: Conversation, messages: List<Message>) =
            ConversationSummary(
                id = conversation.id,
                title = conversation.title,
                createdAt = conversation.createdAt,
                messages = messages,
            )
    }
}

@Serializable
data class NewConversation(
    val title: String,
    val initialMessage: NewMessage?,
)

@Serializable
data class UpdateConversation(
    val title: String,
)

@Serializable
data class ConversationFeedback(
    val liked: Boolean,
)