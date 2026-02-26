package no.nav.nks_ai.api.app

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.feedback.FeedbackId
import no.nav.nks_ai.api.core.ignoredWords.IgnoredWordId
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.notification.NotificationId

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

    class MissingIgnoredWordsId() : BadRequest(
        "This request does not contain the required ignored word id path parameter"
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

    class IgnoredWordNotFound(id: IgnoredWordId?) : ApplicationError(
        code = HttpStatusCode.NotFound,
        message = "Ignored word not found",
        description = id
            ?.let { "Ignored word with id ${id.value} was not found" }
            ?: "Ignored word not found"
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

/**
 * Responds with 200 OK and value of type <T> if result is succesessful,
 * or with ApplicationError mapped to ErrorResponse if failure.
 */
suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: ApplicationResult<T>): ApplicationResult<T> =
    respondResult(HttpStatusCode.OK, result)

/**
 * Responds with statusCode and value of type <T> if result is succesessful,
 * or with ApplicationError mapped to ErrorResponse if failure.
 */
suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    statusCode: HttpStatusCode,
    result: ApplicationResult<T>
): ApplicationResult<T> = result
    .onLeft { error -> respondError(error) }
    .onRight { value -> respond(status = statusCode, message = value) }

/**
 * Responds with 200 OK and value of type <T> if result of either block is succesessful,
 * or with ApplicationError mapped to ErrorResponse if failure.
 */
suspend inline fun <reified T : Any> ApplicationCall.respondEither(
    noinline block: suspend Raise<ApplicationError>.() -> ApplicationResult<T>
): ApplicationResult<T> = respondEither(HttpStatusCode.OK, block)

/**
 * Responds with statusCode and value of type <T> if result of either block is succesessful,
 * or with ApplicationError mapped to ErrorResponse if failure.
 */
suspend inline fun <reified T : Any> ApplicationCall.respondEither(
    statusCode: HttpStatusCode,
    noinline block: suspend Raise<ApplicationError>.() -> ApplicationResult<T>
): ApplicationResult<T> = either {
    val result = block()
    respondResult(statusCode, result).bind()
}

@Serializable
data class ErrorResponse(
    val code: Int,
    val message: String,
    val description: String
)

class InvalidUuidException() : Throwable(message = "Invalid UUID")

fun InvalidUuidException.toError() = ApplicationError.BadRequest(message!!)

class InvalidInputException(message: String) : Throwable(message)

fun InvalidInputException.toError() = ApplicationError.BadRequest(message!!)