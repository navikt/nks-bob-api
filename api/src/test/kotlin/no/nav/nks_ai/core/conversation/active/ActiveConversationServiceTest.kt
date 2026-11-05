package no.nav.nks_ai.core.conversation.active

import io.ktor.client.request.get
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.ConversationRepo
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationService
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.testutil.TestDatabase
import no.nav.nks_ai.testutil.testApp
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActiveConversationServiceTest {
    @Test
    fun `multiple tabs and reconnect are independent of old disconnect`() = activeTest {
        val conversation = conversations.first()
        val first = service.connect(conversation.id, owner).value()
        val sameOwner = NavIdent("T200001")
        assertNotEquals(owner.hash, sameOwner.hash)
        val second = service.connect(conversation.id, sameOwner).value()
        assertNotEquals(first, second)
        assertEquals(setOf(first, second), connectionIds(conversation.id))

        service.disconnect(first).value()
        service.disconnect(first).value()
        assertEquals(setOf(second), connectionIds(conversation.id))
        service.renew(second).value()
        service.disconnect(second).value()
        assertTrue(connectionIds(conversation.id).isEmpty())
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(second).leftOrNull())
    }

    @Test
    fun `wrong owner cannot register another users conversation`() = activeTest {
        val other = NavIdent("T200002")
        assertIs<ApplicationError.ConversationNotFound>(
            service.connect(conversations.first().id, other).leftOrNull()
        )
        assertTrue(connectionIds(conversations.first().id).isEmpty())
        val connectionId = service.connect(conversations.first().id, owner).value()
        assertEquals(setOf(connectionId), connectionIds(conversations.first().id))
    }

    @Test
    fun `expired connections cannot renew and cleanup preserves old live connections`() = activeTest {
        val live = service.connect(conversations[0].id, owner).value()
        val expired = service.connect(conversations[1].id, owner).value()
        age(live, createdSeconds = 86_400, updatedSeconds = 60)
        age(expired, createdSeconds = 86_400, updatedSeconds = 300)

        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(expired).leftOrNull())
        service.renew(live).value()
        assertEquals(1, service.deleteExpiredConnections().value())
        assertEquals(0, service.deleteExpiredConnections().value())
        assertEquals(setOf(live), connectionIds(conversations[0].id))
        assertTrue(connectionIds(conversations[1].id).isEmpty())
        service.renew(live).value()
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(expired).leftOrNull())
    }

    @Test
    fun `deleting a conversation removes only its connections`() = activeTest {
        val first = service.connect(conversations[0].id, owner).value()
        val second = service.connect(conversations[0].id, owner).value()
        val other = service.connect(conversations[1].id, owner).value()
        assertEquals(setOf(first, second), connectionIds(conversations[0].id))
        assertEquals(setOf(other), connectionIds(conversations[1].id))

        ConversationRepo.deleteConversation(conversations[0].id, owner).value()

        assertTrue(connectionIds(conversations[0].id).isEmpty())
        assertEquals(setOf(other), connectionIds(conversations[1].id))
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(first).leftOrNull())
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(second).leftOrNull())
        service.renew(other).value()
    }

    private fun activeTest(block: suspend Fixture.() -> Unit) = testApp { client ->
        client.get("/internal/is_alive")
        val owner = NavIdent("T200001")
        val conversations = (1..3).map {
            ConversationRepo.addConversation(owner, NewConversation("Active fixture $it", null)).value()
        }
        DriverManager.getConnection(
            TestDatabase.jdbcUrl, TestDatabase.container.username, TestDatabase.container.password,
        ).use { connection ->
            try {
                Fixture(ActiveConversationService.create(), owner, conversations, connection).block()
            } finally {
                ConversationRepo.getAllConversations(owner).value().forEach {
                    ConversationRepo.deleteConversation(it.id, owner).value()
                }
            }
        }
    }

    private class Fixture(
        val service: ActiveConversationService,
        val owner: NavIdent,
        val conversations: List<Conversation>,
        val database: Connection,
    ) {
        fun connectionIds(conversationId: ConversationId): Set<UUID> =
            database.prepareStatement(
                "select id from active_conversation_connections where conversation_id = ?"
            ).use { statement ->
                statement.setObject(1, conversationId.value)
                statement.executeQuery().use { result ->
                    buildSet {
                        while (result.next()) add(result.getObject("id", UUID::class.java))
                    }
                }
            }

        fun age(connectionId: UUID, createdSeconds: Int, updatedSeconds: Int) {
            database.prepareStatement(
                """
                update active_conversation_connections
                set created_at = current_timestamp - (? * interval '1 second'),
                    updated_at = current_timestamp - (? * interval '1 second')
                where id = ?
                """.trimIndent()
            ).use {
                it.setInt(1, createdSeconds)
                it.setInt(2, updatedSeconds)
                it.setObject(3, connectionId)
                assertEquals(1, it.executeUpdate())
            }
        }
    }
}

private fun <T : Any> ApplicationResult<T>.value(): T =
    assertNotNull(getOrNull(), leftOrNull()?.description)
