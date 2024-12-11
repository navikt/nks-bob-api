package no.nav.nks_ai.core.message

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.nks_ai.app.now
import java.util.UUID

object MessageIdSerializer : KSerializer<MessageId> {
    override fun deserialize(decoder: Decoder): MessageId {
        return UUID.fromString(decoder.decodeString()).toMessageId()
    }

    override val descriptor: SerialDescriptor
        get() = TODO("Not yet implemented")

    override fun serialize(
        encoder: Encoder,
        messageId: MessageId
    ) {
        encoder.encodeString(messageId.value.toString())
    }
}

@Serializable(MessageIdSerializer::class)
@JvmInline
value class MessageId(@Contextual val value: UUID)

fun UUID.toMessageId() = MessageId(this)

fun ApplicationCall.messageId(name: String = "id"): MessageId? =
    MessageId(UUID.fromString(this.parameters[name]))

@Serializable
enum class MessageType {
    @SerialName("question")
    Question,

    @SerialName("answer")
    Answer,
}

@Serializable
enum class MessageRole {
    @SerialName("human")
    Human,

    @SerialName("ai")
    AI,
}

@Serializable
data class Context(
    val content: String,
    val title: String,
    val ingress: String,
    val source: String,
    val url: String,
    val anchor: String?,
    val articleId: String,
    val articleColumn: String?,
    val lastModified: LocalDateTime?,
    val semanticSimilarity: Double,
)

@Serializable
data class Citation(
    val text: String,
    val sourceId: Int,
)

fun Citation.Companion.fromNewCitation(newCitation: NewCitation) =
    Citation(
        text = newCitation.text,
        sourceId = newCitation.sourceId,
    )

@Serializable
data class NewCitation(
    val text: String,
    val sourceId: Int,
)

@Serializable
data class Feedback(
    val liked: Boolean,
)

fun Feedback.Companion.fromNewFeedback(newFeedback: NewFeedback) =
    Feedback(
        liked = newFeedback.liked
    )

@Serializable
data class NewFeedback(
    val liked: Boolean,
)

@Serializable
data class Message(
    val id: MessageId,
    val content: String,
    val createdAt: LocalDateTime,
    val feedback: Feedback?,
    val messageType: MessageType,
    val messageRole: MessageRole,
    val citations: List<Citation>,
    val context: List<Context>,
    val pending: Boolean
)

fun Message.Companion.answerFrom(
    messageId: MessageId,
    content: String,
    citations: List<NewCitation>,
    context: List<Context>,
    pending: Boolean = true,
) =
    Message(
        id = messageId,
        content = content,
        citations = citations.map(Citation::fromNewCitation),
        context = context,
        createdAt = LocalDateTime.now(),
        messageType = MessageType.Answer,
        messageRole = MessageRole.AI,
        pending = pending,
        feedback = null
    )

@Serializable
data class NewMessage(
    val content: String,
)

@Serializable
data class UpdateMessage(
    val id: MessageId,
    val feedback: Feedback?,
)