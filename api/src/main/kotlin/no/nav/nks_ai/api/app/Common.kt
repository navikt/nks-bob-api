package no.nav.nks_ai.api.app

import arrow.core.Either
import arrow.core.flatten
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import com.sksamuel.aedile.core.Cache
import io.ktor.callid.withCallId
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.core.user.NavIdent
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ComplexExpression
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.append
import org.jetbrains.exposed.v1.core.asLiteral
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.exposedLogger
import org.jetbrains.exposed.v1.dao.EntityChangeType
import org.jetbrains.exposed.v1.dao.EntityHook
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.dao.toEntity
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.util.*
import kotlin.collections.get
import kotlin.time.Clock

suspend fun <T> suspendTransaction(block: Transaction.() -> ApplicationResult<T>): ApplicationResult<T> =
    Either.catch {
        suspendTransaction(statement = block)
    }.mapLeft { throwable ->
        ApplicationError.InternalServerError(
            throwable.message ?: "Database error",
            throwable.stackTraceToString()
        )
    }.flatten()

abstract class BaseTable(name: String) : UUIDTable(name) {
    val createdAt = datetime("created_at")
        .clientDefault { LocalDateTime.now() }

    val updatedAt = datetime("updated_at")
        .clientDefault { LocalDateTime.now() }
}

abstract class BaseEntity(id: EntityID<UUID>, table: BaseTable) : UUIDEntity(id) {
    val createdAt: LocalDateTime by table.createdAt
    var updatedAt: LocalDateTime by table.updatedAt
}

abstract class BaseEntityClass<out E : BaseEntity>(
    table: BaseTable
) : UUIDEntityClass<E>(table) {
    init {
        EntityHook.subscribe { change ->
            val changedEntity = change.toEntity(this)
            when (val type = change.changeType) {
                EntityChangeType.Updated -> {
                    val now = nowUTC()
                    changedEntity?.let {
                        if (it.writeValues[table.updatedAt as Column<*>] == null) {
                            it.updatedAt = now
                        }
                    }
                    logChange(changedEntity, type, now)
                }

                else -> logChange(changedEntity, type)
            }
        }
    }

    private fun nowUTC() = Clock.System.now().toLocalDateTime(TimeZone.UTC)

    private fun logChange(entity: E?, type: EntityChangeType, dateTime: LocalDateTime? = null) {
        entity?.let {
            val entityClassName = this::class.java.enclosingClass.simpleName
            exposedLogger.info(
                "$entityClassName(${it.id}) ${type.name.lowercase()} at ${dateTime ?: nowUTC()}"
            )
        }
    }
}

infix fun <T> ExpressionWithColumnType<T>.bcryptVerified(t: NavIdent): Op<Boolean> =
    BcryptVerifiedOp(this, asLiteral(t.plaintext.value))

class BcryptVerifiedOp(
    val expr1: Expression<*>,
    val expr2: Expression<*>
) : Op<Boolean>(), ComplexExpression {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) =
        queryBuilder {
            append(expr1, " = crypt(", expr2, ", ", expr1, ") ")
        }
}

infix fun Column<List<String>>.has(value: String): Op<Boolean> =
    HasOp(this, value)

class HasOp(
    val column: Column<List<String>>,
    val value: String
) : Op<Boolean>(), ComplexExpression {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) =
        queryBuilder {
            append("'", value, "'", " = ANY(", column, ")")
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

fun ApplicationCall.navIdent(): ApplicationResult<NavIdent> = either {
    getClaim(getIssuerName(), "NAVident")
        ?.let { NavIdent(it) }
        ?: raise(ApplicationError.MissingNavIdent())
}

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

suspend fun <K : Any, V : Any, Err> Cache<K, V>.eitherGet(
    key: K,
    compute: suspend (K) -> Either<Err, V>
): Either<Err, V> = getOrNull(key)?.right()
    ?: compute(key)
        .map {
            put(key, it)
            it
        }

enum class Sort(val value: String) {
    CreatedAtAsc("CREATED_AT_ASC"),
    CreatedAtDesc("CREATED_AT_DESC");

    companion object {
        private val labelToEnum = entries.associateBy { it.value }

        val validValues = entries.toTypedArray().asList().joinToString(", ") { it.value }

        fun fromStringValue(value: String): ApplicationResult<Sort> = either {
            labelToEnum[value]
                ?: raise(ApplicationError.SerializationError("Error parsing sort value $value. Valid values: $validValues"))
        }
    }
}

data class Pagination(val page: Int, val size: Int, val sort: Sort)

fun Parameters.getInt(name: String, default: Int): ApplicationResult<Int> =
    catch({
        val int = get(name)?.toInt() ?: default
        int.right()
    }) {
        ApplicationError.BadRequest("Could not parse number. ${it.message}").left()
    }

fun ApplicationCall.pagination(): ApplicationResult<Pagination> = either {
    val page = parameters.getInt("page", 0).bind()
    val size = parameters.getInt("size", 100).bind()

    val sort = parameters["sort"]
        ?.let { Sort.fromStringValue(it).bind() }
        ?: Sort.CreatedAtDesc

    ensure(page >= 0) {
        ApplicationError.BadRequest("Only positive page values are allowed")
    }
    ensure(size >= 0) {
        ApplicationError.BadRequest("Only positive size values are allowed")
    }
    ensure(size <= 1000) {
        ApplicationError.BadRequest("Cannot fetch more than 1000 elements at once")
    }

    Pagination(page, size, sort)
}

fun <T : BaseEntity> SizedIterable<T>.paginated(pagination: Pagination, table: BaseTable): SizedIterable<T> {
    val order = when (pagination.sort) {
        Sort.CreatedAtAsc -> table.createdAt to SortOrder.ASC
        Sort.CreatedAtDesc -> table.createdAt to SortOrder.DESC
    }

    return orderBy(order)
        .limit(pagination.size)
        .offset((pagination.size * pagination.page).toLong())
}

@Serializable
data class Page<T>(
    val data: List<T>,
    val total: Long,
)