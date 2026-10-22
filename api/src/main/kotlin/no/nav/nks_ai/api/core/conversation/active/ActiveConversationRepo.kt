package no.nav.nks_ai.api.core.conversation.active

import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.toKotlinLocalDateTime
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.toConversationId
import no.nav.nks_ai.api.core.user.NavIdent
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val logger = KotlinLogging.logger {}

internal object ActiveConversationRepo {
    private val leaseSeconds = ActiveConversationService.LEASE_DURATION.inWholeSeconds

    suspend fun connect(conversationId: ConversationId, owner: NavIdent): ApplicationResult<UUID> =
        databaseResult {
            val connectionId = UUID.randomUUID()
            val inserted = exec(
                """
                insert into active_conversation_connections (id, conversation_id, owner)
                select cast(? as uuid), id, ? from conversations
                where id = cast(? as uuid) and owner = crypt(?, owner)
                returning id
                """.trimIndent(),
                listOf(
                    TextColumnType() to connectionId.toString(),
                    TextColumnType() to owner.hash,
                    TextColumnType() to conversationId.value.toString(),
                    TextColumnType() to owner.plaintext.value,
                ),
                StatementType.SELECT,
            ) { it.next() }
            if (inserted == true) connectionId.right()
            else ApplicationError.ConversationNotFound(conversationId).left()
        }

    suspend fun renew(connectionId: UUID): ApplicationResult<Unit> =
        databaseResult {
            val renewed = exec(
                """
                update active_conversation_connections set updated_at = current_timestamp
                where id = cast(? as uuid)
                  and updated_at > current_timestamp - (? * interval '1 second')
                returning id
                """.trimIndent(),
                listOf(
                    TextColumnType() to connectionId.toString(),
                    LongColumnType() to leaseSeconds,
                ),
                StatementType.SELECT,
            ) { it.next() }
            if (renewed == true) Unit.right()
            else ApplicationError.ActiveConnectionExpired().left()
        }

    suspend fun disconnect(connectionId: UUID): ApplicationResult<Unit> =
        databaseResult {
            exec(
                "delete from active_conversation_connections where id = cast(? as uuid)",
                listOf(TextColumnType() to connectionId.toString()),
            )
            Unit.right()
        }

    suspend fun getActiveConversations(
        owner: NavIdent,
        request: ActiveConversationPageRequest,
    ): ApplicationResult<Page<ActiveConversation>> = either {
        ensure(request.page >= 0) { ApplicationError.BadRequest("Page must not be negative") }
        ensure(request.size in 1..1000) { ApplicationError.BadRequest("Size must be between 1 and 1000") }
        // Count and page share the same snapshot and database-time cutoff.
        databaseResult(readOnly = true) {
            val arguments = listOf(
                LongColumnType() to leaseSeconds,
                TextColumnType() to owner.plaintext.value,
            )
            val total = checkNotNull(
                exec(
                    """
                    select count(distinct conversation_id) from active_conversation_connections
                    where updated_at > current_timestamp - (? * interval '1 second')
                      and owner = crypt(?, owner)
                    """.trimIndent(),
                    arguments,
                ) { result ->
                    check(result.next())
                    result.getLong(1)
                }
            )
            val order = when (request.sort) {
                ActiveConversationSort.CREATED_AT_ASC -> "c.created_at asc"
                ActiveConversationSort.CREATED_AT_DESC -> "c.created_at desc"
                ActiveConversationSort.CONNECTED_AT_ASC -> "min(a.created_at) asc"
                ActiveConversationSort.CONNECTED_AT_DESC -> "min(a.created_at) desc"
            }
            val data: List<ActiveConversation> = checkNotNull(
                exec(
                    """
                    select c.id, c.title, c.created_at, min(a.created_at) as connected_at
                    from active_conversation_connections a
                    join conversations c on c.id = a.conversation_id
                    where a.updated_at > current_timestamp - (? * interval '1 second')
                      and a.owner = crypt(?, a.owner)
                    group by c.id, c.title, c.created_at
                    order by $order, c.id asc
                    limit ? offset ?
                    """.trimIndent(),
                    arguments + listOf(
                        LongColumnType() to request.size.toLong(),
                        LongColumnType() to request.page.toLong() * request.size,
                    ),
                ) { result ->
                    buildList<ActiveConversation> {
                        while (result.next()) {
                            add(
                                ActiveConversation(
                                    id = UUID.fromString(result.getString("id")).toConversationId(),
                                    title = result.getString("title"),
                                    createdAt = result.utcDateTime("created_at"),
                                    connectedAt = result.utcDateTime("connected_at"),
                                )
                            )
                        }
                    }
                }
            )
            Page(data, total).right()
        }.bind()
    }

    suspend fun deleteExpiredConnections(): ApplicationResult<Int> =
        databaseResult {
            checkNotNull(
                exec(
                    """
                    with deleted as (
                        delete from active_conversation_connections
                        where updated_at <= current_timestamp - (? * interval '1 second')
                        returning id
                    )
                    select count(*) from deleted
                    """.trimIndent(),
                    listOf(LongColumnType() to leaseSeconds),
                    StatementType.SELECT,
                ) { result ->
                    check(result.next())
                    Math.toIntExact(result.getLong(1))
                }
            ).right()
        }

    private fun ResultSet.utcDateTime(column: String) =
        getObject(column, OffsetDateTime::class.java)
            .withOffsetSameInstant(ZoneOffset.UTC)
            .toLocalDateTime()
            .toKotlinLocalDateTime()

    private suspend fun <T> databaseResult(
        readOnly: Boolean = false,
        block: JdbcTransaction.() -> ApplicationResult<T>,
    ): ApplicationResult<T> =
        try {
            suspendTransaction(
                transactionIsolation = if (readOnly) Connection.TRANSACTION_REPEATABLE_READ
                else Connection.TRANSACTION_READ_COMMITTED,
                readOnly = readOnly,
            ) {
                block()
            }
        } catch (exception: SQLException) {
            logger.error { "Active connection database operation failed (SQL state ${exception.sqlState})" }
            ApplicationError.InternalServerError("Database error", "Active connection operation failed").left()
        }
}
