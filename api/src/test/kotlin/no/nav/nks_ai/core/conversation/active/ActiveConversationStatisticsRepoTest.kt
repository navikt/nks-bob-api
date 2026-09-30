package no.nav.nks_ai.core.conversation.active

import io.ktor.client.request.get
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationStatisticsRepo
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.testutil.TestDatabase
import no.nav.nks_ai.testutil.testApp
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActiveConversationStatisticsRepoTest {
    @Test
    fun `counts unique live conversations and excludes expired connections`() = testApp { client ->
        client.get("/internal/is_alive")
        val baseline = assertNotNull(ActiveConversationStatisticsRepo.getStatistics().getOrNull())
        val liveConversation = UUID.randomUUID()
        val expiredConversation = UUID.randomUUID()
        val owner = NavIdent("T100001").hash

        DriverManager.getConnection(
            TestDatabase.jdbcUrl,
            TestDatabase.container.username,
            TestDatabase.container.password,
        ).use { connection ->
            try {
                connection.prepareStatement(
                    "insert into conversations (id, title, owner, created_at) values (?, ?, ?, current_timestamp)"
                ).use { insert ->
                    for (id in listOf(liveConversation, expiredConversation)) {
                        insert.setObject(1, id)
                        insert.setString(2, "Statistics fixture")
                        insert.setString(3, owner)
                        insert.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    """
                    insert into active_conversation_connections
                        (id, conversation_id, owner, created_at, updated_at)
                    values (?, ?, ?, current_timestamp - interval '12 hours',
                            current_timestamp - (? * interval '1 second'))
                    """.trimIndent()
                ).use { insert ->
                    for ((conversationId, ageSeconds) in listOf(
                        liveConversation to 0,
                        liveConversation to 0,
                        liveConversation to 600,
                        expiredConversation to 600,
                    )) {
                        insert.setObject(1, UUID.randomUUID())
                        insert.setObject(2, conversationId)
                        insert.setString(3, owner)
                        insert.setInt(4, ageSeconds)
                        insert.executeUpdate()
                    }
                }

                val actual = assertNotNull(ActiveConversationStatisticsRepo.getStatistics().getOrNull())
                assertEquals(baseline.conversations + 1, actual.conversations)
                assertEquals(baseline.connections + 2, actual.connections)
                assertTrue(actual.measuredAtEpochSeconds >= baseline.measuredAtEpochSeconds)
            } finally {
                connection.prepareStatement("delete from conversations where id in (?, ?)").use { delete ->
                    delete.setObject(1, liveConversation)
                    delete.setObject(2, expiredConversation)
                    delete.executeUpdate()
                }
            }
        }
        val afterDeletion = assertNotNull(ActiveConversationStatisticsRepo.getStatistics().getOrNull())
        assertEquals(baseline.conversations, afterDeletion.conversations)
        assertEquals(baseline.connections, afterDeletion.connections)
    }
}
