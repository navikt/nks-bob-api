package no.nav.nks_ai.integration

import arrow.core.getOrElse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import no.nav.nks_ai.core.conversation.ConversationRepo
import no.nav.nks_ai.core.conversation.NewConversation
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRepo
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.MessageType
import no.nav.nks_ai.core.user.NavIdent

/**
 * Integration tests for Message API.
 * Tests the full stack from HTTP API through service layer to database.
 */
class MessageApiIntegrationTest : ApiIntegrationTestBase() {

    private val messageTestNavIdent = NavIdent("Z777777")

    private suspend fun createTestConversationAndMessage(content: String = "Test message") =
        ConversationRepo.addConversation(
            messageTestNavIdent,
            NewConversation("Test Conversation", null)
        ).getOrElse { throw AssertionError("Failed to create test conversation") }
            .let { conversation ->
                MessageRepo.addMessage(
                    conversationId = conversation.id,
                    messageContent = content,
                    messageType = MessageType.Answer,
                    messageRole = MessageRole.AI,
                    createdBy = "assistant",
                    context = emptyList(),
                    citations = emptyList(),
                    pending = false
                ).getOrElse { throw AssertionError("Failed to create test message") }
            }

    @Test
    fun `GET message by ID should return specific message`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage("Hello, this is a test") }

        val response = client.get("/api/v1/messages/${message.id.value}") {
            withTestAuth("Z777777")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val retrieved = response.body<Message>()
        assertEquals(message.id, retrieved.id)
        assertEquals("Hello, this is a test", retrieved.content)
        assertEquals(MessageType.Answer, retrieved.messageType)
        assertEquals(MessageRole.AI, retrieved.messageRole)
    }

    @Test
    fun `GET message by ID should return 404 for different user`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        // Try to access with different user (returns 404 to avoid leaking message existence)
        val response = client.get("/api/v1/messages/${message.id.value}") {
            withTestAuth("Z666666")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET message by ID should return 404 for non-existent message`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val fakeId = java.util.UUID.randomUUID()
        val response = client.get("/api/v1/messages/$fakeId") {
            withTestAuth("Z777777")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

//    @Test
//    fun `PUT message should update message content`() = testApplication {
//        application { testModule() }
//        val client = createJsonClient()
//
//        val message = runBlocking { createTestConversationAndMessage("Original content") }
//
//        val response = client.put("/api/v1/messages/${message.id.value}") {
//            withTestAuth("Z777777")
//            contentType(ContentType.Application.Json)
//            setBody(UpdateMessage(content = "Updated content"))
//        }
//
//        assertEquals(HttpStatusCode.OK, response.status)
//        val updated = response.body<Message>()
//        assertEquals(message.id, updated.id)
//        assertEquals("Updated content", updated.content)
//        assertNotEquals(message.updated, updated.updated)
//    }
//
//    @Test
//    fun `PUT message should return 404 for non-existent message`() = testApplication {
//        application { testModule() }
//        val client = createJsonClient()
//
//        val fakeId = java.util.UUID.randomUUID()
//        val response = client.put("/api/v1/messages/$fakeId") {
//            withTestAuth("Z777777")
//            contentType(ContentType.Application.Json)
//            setBody(UpdateMessage(content = "Updated content"))
//        }
//
//        assertEquals(HttpStatusCode.NotFound, response.status)
//    }

//    @Test
//    fun `message operations should respect user isolation`() = testApplication {
//        application { testModule() }
//        val client = createJsonClient()
//
//        // User 1 creates a message
//        val user1Message = runBlocking {
//            val conversation = ConversationRepo.addConversation(
//                NavIdent("Z111111"),
//                NewConversation("User 1 Conversation", null)
//            ).getOrElse { throw AssertionError("Failed to create conversation") }
//
//            MessageRepo.addMessage(
//                conversationId = conversation.id,
//                messageContent = "User 1 message",
//                messageType = MessageType.Answer,
//                messageRole = MessageRole.AI,
//                createdBy = "assistant",
//                context = emptyList(),
//                citations = emptyList(),
//                pending = false
//            ).getOrElse { throw AssertionError("Failed to create message") }
//        }
//
//        // User 2 should not be able to access User 1's message
//        val getResponse = client.get("/api/v1/messages/${user1Message.id.value}") {
//            withTestAuth("Z222222")
//            contentType(ContentType.Application.Json)
//        }
//        assertEquals(HttpStatusCode.NotFound, getResponse.status)
//
//        // User 2 should not be able to update User 1's message
//        val updateResponse = client.put("/api/v1/messages/${user1Message.id.value}") {
//            withTestAuth("Z222222")
//            contentType(ContentType.Application.Json)
//            setBody(UpdateMessage(content = "Hacked content"))
//        }
//        assertEquals(HttpStatusCode.NotFound, updateResponse.status)
//    }
}
