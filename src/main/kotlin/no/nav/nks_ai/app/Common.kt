package no.nav.nks_ai.app

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.core.user.NavIdent
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block)

fun LocalDateTime.Companion.now() =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun ApplicationCall.getClaim(issuer: String, name: String) =
    authentication.principal<JWTPrincipal>()
        ?.payload
        ?.getClaim(name)
        ?.asString()

fun ApplicationCall.getIssuerName(): String = Config.issuers.head.issuer_name

fun ApplicationCall.getNavIdent(): NavIdent? =
    getClaim(getIssuerName(), "NAVident")
        ?.let { NavIdent(it) }

open class ApplicationError(
    open val code: HttpStatusCode,
    open val message: String,
    open val description: String,
) {
    fun toErrorResponse() = ErrorResponse(
        code = code.value,
        message = message,
        description = description,
    )

    companion object
}

fun ApplicationError.Companion.fromThrowable(throwable: Throwable) = ApplicationError(
    code = HttpStatusCode.InternalServerError,
    message = throwable.message ?: "An unexpected error occurred",
    description = throwable.cause?.message ?: ""
)

suspend fun ApplicationCall.respondError(error: ApplicationError) =
    respond(error.code, error.toErrorResponse())

@Serializable
data class ErrorResponse(
    val code: Int,
    val message: String,
    val description: String
)

fun Route.sse(
    path: String,
    method: HttpMethod,
    handler: suspend ServerSSESession.() -> Unit
) {
    plugin(SSE)

    route(path, method) {
        sse(handler)
    }
}
