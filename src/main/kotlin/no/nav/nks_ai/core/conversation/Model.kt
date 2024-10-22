package no.nav.nks_ai.core.conversation

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.core.message.NewMessage
import java.util.UUID

@Serializable
data class Conversation(
    val id: String,
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
    class ConversationNotFound(id: UUID) : ConversationError(
        HttpStatusCode.NotFound,
        "Conversation not found",
        "Conversation with id $id not found"
    )
}