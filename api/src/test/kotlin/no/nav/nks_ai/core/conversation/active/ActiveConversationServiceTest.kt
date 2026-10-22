package no.nav.nks_ai.core.conversation.active

import io.ktor.client.request.get
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationRepo
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationPageRequest
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationService
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationSort
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
    fun `multiple tabs are deduplicated and reconnect is independent of old disconnect`() = activeTest {
        val conversation = conversations.first()
        val first = service.connect(conversation.id, owner).value()
        val sameOwner = NavIdent("T200001")
        assertNotEquals(owner.hash, sameOwner.hash)
        val second = service.connect(conversation.id, sameOwner).value()
        assertNotEquals(first, second)
        assertEquals(listOf(conversation.id), page().data.map { it.id })
        assertEquals(1L, page().total)

        service.disconnect(first).value()
        service.disconnect(first).value()
        assertEquals(1L, page().total)
        service.disconnect(second).value()
        assertEquals(0L, page().total)
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(second).leftOrNull())
    }

    @Test
    fun `wrong owner cannot register or list another users conversations`() = activeTest {
        val other = NavIdent("T200002")
        assertIs<ApplicationError.ConversationNotFound>(
            service.connect(conversations.first().id, other).leftOrNull()
        )
        service.connect(conversations.first().id, owner).value()
        assertEquals(0L, service.getActiveConversations(other, ActiveConversationPageRequest()).value().total)
    }

    @Test
    fun `expired connections cannot renew and cleanup preserves old live connections`() = activeTest {
        val live = service.connect(conversations[0].id, owner).value()
        val expired = service.connect(conversations[1].id, owner).value()
        age(live, createdSeconds = 86_400, updatedSeconds = 60)
        age(expired, createdSeconds = 86_400, updatedSeconds = 300)

        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(expired).leftOrNull())
        service.renew(live).value()
        assertEquals(listOf(conversations[0].id), page().data.map { it.id })
        assertEquals(1, service.deleteExpiredConnections().value())
        assertEquals(0, service.deleteExpiredConnections().value())
        service.renew(live).value()
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(expired).leftOrNull())
    }

    @Test
    fun `all sort orders use live connections and total is independent of page size`() = activeTest {
        val ids = conversations.map { it.id }
        for ((index, conversation) in conversations.withIndex()) {
            database.prepareStatement(
                "update conversations set created_at = current_timestamp - (? * interval '1 second') where id = ?"
            ).use {
                it.setInt(1, 300 - index * 100)
                it.setObject(2, conversation.id.value)
                it.executeUpdate()
            }
            val connectionId = service.connect(conversation.id, owner).value()
            age(connectionId, listOf(20, 10, 30)[index], 0)
        }
        age(service.connect(ids[0], owner).value(), 5, 0)
        age(service.connect(ids[1], owner).value(), 600, 600)

        val expected = mapOf(
            ActiveConversationSort.CREATED_AT_ASC to ids,
            ActiveConversationSort.CREATED_AT_DESC to ids.reversed(),
            ActiveConversationSort.CONNECTED_AT_ASC to listOf(ids[2], ids[0], ids[1]),
            ActiveConversationSort.CONNECTED_AT_DESC to listOf(ids[1], ids[0], ids[2]),
        )
        for ((sort, order) in expected) {
            val actual = service.getActiveConversations(owner, ActiveConversationPageRequest(sort = sort)).value()
            assertEquals(order, actual.data.map { it.id })
            assertEquals(3L, actual.total)
            val middle = service.getActiveConversations(owner, ActiveConversationPageRequest(1, 1, sort)).value()
            assertEquals(listOf(order[1]), middle.data.map { it.id })
            assertEquals(3L, middle.total)
        }
        val beyondLast = service.getActiveConversations(owner, ActiveConversationPageRequest(100, 1)).value()
        assertTrue(beyondLast.data.isEmpty())
        assertEquals(3L, beyondLast.total)
    }

    @Test
    fun `equal timestamps use stable conversation id ordering`() = activeTest {
        for (conversation in conversations) {
            val connectionId = service.connect(conversation.id, owner).value()
            database.prepareStatement("update conversations set created_at = '2020-01-01' where id = ?").use {
                it.setObject(1, conversation.id.value)
                it.executeUpdate()
            }
            database.prepareStatement(
                "update active_conversation_connections set created_at = '2020-01-01' where id = ?"
            ).use {
                it.setObject(1, connectionId)
                it.executeUpdate()
            }
        }
        for (sort in ActiveConversationSort.entries) {
            assertEquals(
                conversations.map { it.id }.sortedBy { it.value.toString() },
                service.getActiveConversations(owner, ActiveConversationPageRequest(sort = sort)).value().data.map { it.id },
            )
        }
    }

    @Test
    fun `deleting a conversation removes connections and statistics count unique conversations`() = activeTest {
        val baseline = service.getStatistics().value()
        val first = service.connect(conversations[0].id, owner).value()
        service.connect(conversations[0].id, owner).value()
        service.connect(conversations[1].id, owner).value()
        val actual = service.getStatistics().value()
        assertEquals(baseline.conversations + 2, actual.conversations)
        assertEquals(baseline.connections + 3, actual.connections)
        ConversationRepo.deleteConversation(conversations[0].id, owner).value()
        assertIs<ApplicationError.ActiveConnectionExpired>(service.renew(first).leftOrNull())
        assertEquals(1L, page().total)
    }

    @Test
    fun `invalid pagination is rejected`() = activeTest {
        for (request in listOf(
            ActiveConversationPageRequest(page = -1),
            ActiveConversationPageRequest(size = 0),
            ActiveConversationPageRequest(size = 1001),
        )) {
            assertIs<ApplicationError.BadRequest>(service.getActiveConversations(owner, request).leftOrNull())
        }
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
        suspend fun page() = service.getActiveConversations(owner, ActiveConversationPageRequest()).value()

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
