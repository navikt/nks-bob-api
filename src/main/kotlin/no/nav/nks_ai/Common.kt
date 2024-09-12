package no.nav.nks_ai

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import no.nav.security.token.support.v2.TokenValidationContextPrincipal
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block)

fun LocalDateTime.Companion.now() =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun ApplicationCall.getClaim(issuer: String, name: String) =
    authentication.principal<TokenValidationContextPrincipal>()
        ?.context
        ?.getClaims(issuer)
        ?.getStringClaim(name)

fun ApplicationCall.getIssuerName(): String? =
    application.environment.config
        .configList("no.nav.security.jwt.issuers")
        .getOrNull(0)
        ?.propertyOrNull("issuer_name")
        ?.getString()

fun ApplicationCall.getNavIdent(): String? =
    getIssuerName()?.let { issuer ->
        getClaim(issuer, "NAVident")
    }

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