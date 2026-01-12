package no.nav.nks_ai.integration

import arrow.core.getOrElse
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import no.nav.nks_ai.core.conversation.Conversation
import no.nav.nks_ai.core.conversation.ConversationRepo
import no.nav.nks_ai.core.conversation.ConversationSummary
import no.nav.nks_ai.core.conversation.NewConversation
import no.nav.nks_ai.core.message.MessageRepo
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.MessageType
import no.nav.nks_ai.core.user.NavIdent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Admin API.
 * Tests the full stack from HTTP API through service layer to database.
 * Admin endpoints allow accessing any user's data for support purposes.
 */
class AdminIntegrationTest : ApiIntegrationTestBase() {

    private val adminTestNavIdent = NavIdent("Z123456")

    private suspend fun createTestConversation(navIdent: NavIdent, title: String = "Test Conversation") =
        ConversationRepo.addConversation(
            navIdent,
            NewConversation(title, null)
        ).getOrElse { throw AssertionError("Failed to create test conversation") }

    private suspend fun createTestMessage(conversationId: no.nav.nks_ai.core.conversation.ConversationId, content: String) =
        MessageRepo.addMessage(
            conversationId = conversationId,
            messageContent = content,
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Failed to create test message") }

    @Test
    fun `GET admin conversation by ID should return any user's conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val conversation = runBlocking { createTestConversation(adminTestNavIdent, "Admin Test Conversation") }

        val response = client.get("/api/v1/admin/conversations/${conversation.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val retrieved = response.body<Conversation>()
        assertEquals(conversation.id, retrieved.id)
        assertEquals("Admin Test Conversation", retrieved.title)
    }

    @Test
    fun `GET admin conversation should return 404 for non-existent conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val fakeId = java.util.UUID.randomUUID()
        val response = client.get("/api/v1/admin/conversations/$fakeId") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET admin conversation summary should return conversation details`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val conversation = runBlocking { createTestConversation(adminTestNavIdent, "Summary Test") }

        val response = client.get("/api/v1/admin/conversations/${conversation.id.value}/summary") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val summary = response.body<ConversationSummary>()
        assertEquals(conversation.id, summary.id)
        assertEquals("Summary Test", summary.title)
    }

    @Test
    fun `GET admin conversation messages should return messages for conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val conversation = runBlocking { createTestConversation(adminTestNavIdent, "Messages Test") }
        runBlocking {
            createTestMessage(conversation.id, "First message")
            createTestMessage(conversation.id, "Second message")
        }

        val response = client.get("/api/v1/admin/conversations/${conversation.id.value}/messages") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val messages = response.body<List<no.nav.nks_ai.core.message.Message>>()
        assertTrue(messages.size >= 2)
    }

    @Test
    fun `GET conversation from message ID should return conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val conversation = runBlocking { createTestConversation(adminTestNavIdent, "Message Lookup Test") }
        val message = runBlocking { createTestMessage(conversation.id, "Test message for lookup") }

        val response = client.get("/api/v1/admin/messages/${message.id.value}/conversation") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val retrieved = response.body<Conversation>()
        assertEquals(conversation.id, retrieved.id)
    }

    @Test
    fun `GET conversation summary from message ID should return summary`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val conversation = runBlocking { createTestConversation(adminTestNavIdent, "Summary Lookup Test") }
        val message = runBlocking { createTestMessage(conversation.id, "Test message") }

        val response = client.get("/api/v1/admin/messages/${message.id.value}/conversation/summary") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val summary = response.body<ConversationSummary>()
        assertEquals(conversation.id, summary.id)
        assertTrue(summary.messages.size >= 1)
    }

    @Test
    fun `GET message conversation should return 404 for non-existent message`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val fakeId = java.util.UUID.randomUUID()
        val response = client.get("/api/v1/admin/messages/$fakeId/conversation") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `admin endpoints should work across different users' data`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // Create conversations for different users
        val user1Conversation = runBlocking { createTestConversation(NavIdent("Z111111"), "User 1 Conversation") }
        val user2Conversation = runBlocking { createTestConversation(NavIdent("Z222222"), "User 2 Conversation") }

        // Admin can access both
        val response1 = client.get("/api/v1/admin/conversations/${user1Conversation.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, response1.status)

        val response2 = client.get("/api/v1/admin/conversations/${user2Conversation.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, response2.status)
    }
}
