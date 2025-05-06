package no.nav.nks_ai.app

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.feedback.FeedbackId
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

    class MissingAccess() : ApplicationError(
        code = HttpStatusCode.Forbidden,
        message = "Forbidden",
        description = "This user does not have access to this resource"
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

    class MissingFeedbackId() : BadRequest(
        "This request does not contain the required feedback id path parameter"
    )

    class SerializationError(description: String) : BadRequest(description)

    class MessageNotFound(messageId: MessageId?) : ApplicationError(
        code = HttpStatusCode.NotFound,
        message = "Message not found",
        description = messageId
            ?.let { "Message with id ${messageId.value} was not found" }
            ?: "Message not found",
    )

    class ConversationNotFound(conversationId: ConversationId?) : ApplicationError(
        code = HttpStatusCode.NotFound,
        message = "Conversation not found",
        description = conversationId
            ?.let { "Conversation with id ${conversationId.value} was not found" }
            ?: "Conversation not found",
    )

    class UserConfigNotFound() : ApplicationError(
        code = HttpStatusCode.NotFound,
        message = "User config not found",
        description = "User config not found",
    )

    class NotificationNotFound(notificationId: NotificationId?) : ApplicationError(
        code = HttpStatusCode.NotFound,
        message = "Notification not found",
        description = notificationId
            ?.let { "Notification with id ${notificationId.value} was not found" }
            ?: "Notification not found"
    )

    class FeedbackNotFound(feedbackId: FeedbackId?) : ApplicationError(
        code = HttpStatusCode.NotFound,
        message = "Feedback not found",
        description = feedbackId
            ?.let { "Feedback with id ${feedbackId.value} was not found" }
            ?: "Feedback not found"
    )

    class InvalidInput(message: String?, description: String?) : ApplicationError(
        code = HttpStatusCode.InternalServerError,
        message = message ?: "Invalid input",
        description = description ?: "Invalid input",
    )
}

typealias ApplicationResult<T> = Either<ApplicationError, T>

suspend fun ApplicationCall.respondError(error: ApplicationError) =
    respond(error.code, error.toErrorResponse())

suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: ApplicationResult<T>): ApplicationResult<T> =
    respondResult(HttpStatusCode.OK, result)

suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    statusCode: HttpStatusCode,
    result: ApplicationResult<T>
): ApplicationResult<T> = result
    .onLeft { error -> respondError(error) }
    .onRight { value -> respond(status = statusCode, message = value) }

@Serializable
data class ErrorResponse(
    val code: Int,
    val message: String,
    val description: String
)

class InvalidUuidException() : Throwable(message = "Invalid UUID")

fun InvalidUuidException.toError() = ApplicationError.BadRequest(message!!)