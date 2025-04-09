package no.nav.nks_ai.app

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.notification.NotificationId

sealed class ApplicationError(
    val code: HttpStatusCode,
    val message: String,
    val description: String,
) {
    fun toErrorResponse() = ErrorResponse(
        code = code.value,
        message = message,
        description = description,
    )

    class InternalServerError(
        message: String,
        description: String,
    ) : ApplicationError(HttpStatusCode.InternalServerError, message, description)

    class Unauthorized() : ApplicationError(
        code = HttpStatusCode.Unauthorized,
        message = "Unauthorized",
        description = "This user does not have access to this resource"
    )

    class MissingNavIdent() : ApplicationError(
        code = HttpStatusCode.Forbidden,
        message = "Forbidden",
        description = "This request does not contain the required NAVident claim"
    )

    open class BadRequest(description: String) : ApplicationError(
        code = HttpStatusCode.BadRequest,
        message = "Bad request",
        description = description,
    )

    class MissingConversationId() : BadRequest(
        "This request does not contain the required conversation id path parameter"
    )

    class MissingMessageId() : BadRequest(
        "This request does not contain the required message id path parameter"
    )

    class MissingNotificationId() : BadRequest(
        "This request does not contain the required notification id path parameter"
    )

    class SerializationError(description: String) : BadRequest(description)
}

sealed class DomainError(
    code: HttpStatusCode,
    message: String,
    description: String,
) : ApplicationError(code, message, description) {
    class MessageNotFound(messageId: MessageId?) : DomainError(
        code = HttpStatusCode.NotFound,
        message = "Message not found",
        description = messageId
            ?.let { "Message with id ${messageId.value} was not found" }
            ?: "Message not found",
    )

    class ConversationNotFound(conversationId: ConversationId?) : DomainError(
        code = HttpStatusCode.NotFound,
        message = "Conversation not found",
        description = conversationId
            ?.let { "Conversation with id ${conversationId.value} was not found" }
            ?: "Conversation not found",
    )

    class UserConfigNotFound() : DomainError(
        code = HttpStatusCode.NotFound,
        message = "User config not found",
        description = "User config not found",
    )

    class NotificationNotFound(notificationId: NotificationId?) : DomainError(
        code = HttpStatusCode.NotFound,
        message = "Notification not found",
        description = notificationId
            ?.let { "Notification with id ${notificationId.value} was not found" }
            ?: "Notification not found"
    )

    class InvalidInput(message: String?, description: String?) : DomainError(
        code = HttpStatusCode.InternalServerError,
        message = message ?: "Invalid input",
        description = description ?: "Invalid input",
    )
}

typealias ApplicationResult<T> = Either<ApplicationError, T>

typealias DomainResult<T> = Either<DomainError, T>

suspend fun ApplicationCall.respondError(error: ApplicationError) =
    respond(error.code, error.toErrorResponse())

@Serializable
data class ErrorResponse(
    val code: Int,
    val message: String,
    val description: String
)

