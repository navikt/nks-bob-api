package no.nav.nks_ai.api.core.conversation.active

import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.user.NavIdent
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.Connection
import java.sql.SQLException
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

    private suspend fun <T> databaseResult(
        block: JdbcTransaction.() -> ApplicationResult<T>,
    ): ApplicationResult<T> =
        try {
            suspendTransaction(
                transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
                readOnly = false,
            ) {
                block()
            }
        } catch (exception: SQLException) {
            logger.error { "Active connection database operation failed (SQL state ${exception.sqlState})" }
            ApplicationError.InternalServerError("Database error", "Active connection operation failed").left()
        }
}
