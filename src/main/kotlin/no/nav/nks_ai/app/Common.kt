package no.nav.nks_ai.app

import arrow.core.Either
import arrow.core.right
import com.sksamuel.aedile.core.Cache
import io.ktor.callid.withCallId
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.header
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
import no.nav.nks_ai.core.user.NavIdent
import org.jetbrains.exposed.sql.ComplexExpression
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.append
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block)

infix fun <T> ExpressionWithColumnType<T>.bcryptVerified(t: NavIdent): Op<Boolean> =
    BcryptVerifiedOp(this, Expression.build { asLiteral(t.plaintext.value) })

class BcryptVerifiedOp(
    val expr1: Expression<*>,
    val expr2: Expression<*>
) : Op<Boolean>(), ComplexExpression {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) =
        queryBuilder {
            append(expr1, " = crypt(", expr2, ", ", expr1, ") ")
        }
}

fun LocalDateTime.Companion.now() =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun String.truncate(limit: Int): String =
    if (length + 3 > limit) {
        this.substring(0..limit - 4) + "..."
    } else {
        this
    }

fun String.toUUID(): UUID =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        throw InvalidUuidException()
    }

fun ApplicationCall.getClaim(issuer: String, name: String) =
    authentication.principal<JWTPrincipal>()
        ?.payload
        ?.getClaim(name)
        ?.asString()

fun ApplicationCall.getIssuerName(): String = Config.issuers.head.issuer_name

fun ApplicationCall.getNavIdent(): NavIdent? =
    getClaim(getIssuerName(), "NAVident")
        ?.let { NavIdent(it) }

fun Route.sse(
    path: String,
    method: HttpMethod,
    handler: suspend ServerSSESession.() -> Unit
) {
    plugin(SSE)

    route(path, method) {
        sse {
            val callId = call.request.header("nav-call-id")
                ?: UUID.randomUUID().toString()

            withCallId(callId) {
                handler()
            }
        }
    }
}

suspend fun <K, V, Err> Cache<K, V>.eitherGet(
    key: K,
    compute: suspend (K) -> Either<Err, V>
): Either<Err, V> = getOrNull(key)?.right()
    ?: compute(key)
        .map {
            put(key, it)
            it
        }
