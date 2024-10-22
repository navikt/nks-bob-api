package no.nav.nks_ai.message

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import no.nav.nks_ai.citation.Citation
import no.nav.nks_ai.feedback.Feedback

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
    val metadata: JsonObject,
)

@Serializable
data class Message(
    val id: String,
    val content: String,
    val createdAt: LocalDateTime,
    val feedback: Feedback?,
    val messageType: MessageType,
    val messageRole: MessageRole,
    val createdBy: String,
    val citations: List<Citation>,
    val context: List<Context>,
)

@Serializable
data class NewMessage(
    val content: String,
)

@Serializable
data class UpdateMessage(
    val id: String,
    val feedback: Feedback?,
)