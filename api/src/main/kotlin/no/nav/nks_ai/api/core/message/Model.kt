package no.nav.nks_ai.api.core.message

import io.ktor.server.application.ApplicationCall
import java.util.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.nks_ai.api.app.now
import no.nav.nks_ai.api.app.toUUID

object MessageIdSerializer : KSerializer<MessageId> {
    override fun deserialize(decoder: Decoder): MessageId {
        return decoder.decodeString().toUUID().toMessageId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("MessageId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: MessageId
    ) {
        encoder.encodeString(value.value.toString())
    }
}

@Serializable(MessageIdSerializer::class)
@JvmInline
value class MessageId(@Contextual val value: UUID)

fun UUID.toMessageId() = MessageId(this)

fun ApplicationCall.messageId(name: String = "id"): MessageId? =
    this.parameters[name]?.toUUID()?.toMessageId()

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
data class MessageError(
    val title: String,
    val description: String
)

@Serializable
data class Message(
    val id: MessageId,
    val content: String,
    val createdAt: LocalDateTime,
    val messageType: MessageType,
    val messageRole: MessageRole,
    val citations: List<Citation>,
    val context: List<Context>,
    val pending: Boolean,
    val errors: List<MessageError>,
    val followUp: List<String>,
    val userQuestion: String?,
    val contextualizedQuestion: String?,
    val starred: Boolean,
    val tools: List<String>,
)

fun Message.Companion.answerFrom(
    messageId: MessageId,
    content: String,
    citations: List<NewCitation>,
    context: List<Context>,
    followUp: List<String>,
    pending: Boolean = true,
    userQuestion: String?,
    contextualizedQuestion: String?,
    tools: List<String>,
) =
    Message(
        id = messageId,
        content = content,
        citations = citations.map(Citation::fromNewCitation),
        context = context,
        createdAt = LocalDateTime.Companion.now(),
        messageType = MessageType.Answer,
        messageRole = MessageRole.AI,
        pending = pending,
        errors = emptyList(),
        followUp = followUp,
        userQuestion = userQuestion,
        contextualizedQuestion = contextualizedQuestion,
        starred = false,
        tools = tools,
    )

@Serializable
data class NewMessage(
    val content: String,
)

@Serializable
data class UpdateMessage(
    val id: MessageId,
    val starred: Boolean,
)