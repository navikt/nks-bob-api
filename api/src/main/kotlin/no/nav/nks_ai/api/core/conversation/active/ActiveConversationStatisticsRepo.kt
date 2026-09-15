package no.nav.nks_ai.api.core.conversation.active

import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

object ActiveConversationStatisticsRepo {
    suspend fun getStatistics(): ApplicationResult<ActiveConversationStatistics> =
        try {
            suspendTransaction {
                checkNotNull(
                    exec(
                        """
                        select count(distinct conversation_id) as conversations,
                               count(*) as connections,
                               extract(epoch from current_timestamp) as measured_at
                        from active_conversation_connections
                        where updated_at > current_timestamp - (? * interval '1 second')
                        """.trimIndent(),
                        args = listOf(LongColumnType() to ActiveConversationService.LEASE_DURATION.inWholeSeconds),
                    ) { result ->
                        check(result.next()) { "Active connection statistics returned no row" }
                        ActiveConversationStatistics(
                            conversations = result.getLong("conversations"),
                            connections = result.getLong("connections"),
                            measuredAtEpochSeconds = result.getDouble("measured_at"),
                        )
                    }
                )
            }.right()
        } catch (exception: SQLException) {
            logger.error { "Could not read active connection statistics (SQL state ${exception.sqlState})" }
            ApplicationError.InternalServerError(
                "Database error",
                "Could not read active connection statistics",
            ).left()
        }
}
