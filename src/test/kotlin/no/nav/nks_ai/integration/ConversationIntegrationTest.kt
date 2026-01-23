package no.nav.nks_ai.integration

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.nks_ai.core.conversation.*
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.NewMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Conversation API.
 * Tests the full stack from HTTP API through service layer to database.
 */
class ConversationIntegrationTest : ApiIntegrationTestBase() {

    @Test
    fun `POST conversation should create new conversation without initial message`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val response = client.post("/api/v1/conversations") {
            withTestAuth("Z111111")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Test Conversation",
                initialMessage = null
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val conversation = response.body<Conversation>()
        assertEquals("Test Conversation", conversation.title)
    }

    @Test
    fun `GET conversations should return all user conversations`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // Create multiple conversations
        repeat(3) { i ->
            client.post("/api/v1/conversations") {
                withTestAuth("Z222222")
                contentType(ContentType.Application.Json)
                setBody(NewConversation(
                    title = "Conversation $i",
                    initialMessage = null
                ))
            }
        }

        val response = client.get("/api/v1/conversations") {
            withTestAuth("Z222222")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val conversations = response.body<List<Conversation>>()
        assertTrue(conversations.size >= 3)
    }

    @Test
    fun `GET conversation by ID should return specific conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z333333")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Specific Conversation",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        val response = client.get("/api/v1/conversations/${created.id.value}") {
            withTestAuth("Z333333")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val retrieved = response.body<Conversation>()
        assertEquals(created.id, retrieved.id)
        assertEquals("Specific Conversation", retrieved.title)
    }

    @Test
    fun `GET conversation by ID should return 404 for different user`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z444444")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Private Conversation",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        // Try to access with different user (returns 404 to avoid leaking conversation existence)
        val response = client.get("/api/v1/conversations/${created.id.value}") {
            withTestAuth("Z555555")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT conversation should update title`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z666666")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Original Title",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        val updateResponse = client.put("/api/v1/conversations/${created.id.value}") {
            withTestAuth("Z666666")
            contentType(ContentType.Application.Json)
            setBody(UpdateConversation(
                title = "Updated Title"
            ))
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = updateResponse.body<Conversation>()
        assertEquals("Updated Title", updated.title)
    }

    @Test
    fun `DELETE conversation should remove conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z777777")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "To Delete",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        val deleteResponse = client.delete("/api/v1/conversations/${created.id.value}") {
            withTestAuth("Z777777")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // Verify it's gone
        val getResponse = client.get("/api/v1/conversations/${created.id.value}") {
            withTestAuth("Z777777")
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `GET conversation messages should return empty list for new conversation`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Empty Conversation",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        val response = client.get("/api/v1/conversations/${created.id.value}/messages") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val messages = response.body<List<Message>>()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `POST conversation feedback should accept thumbs up`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z999999")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Feedback Test",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        val feedbackResponse = client.post("/api/v1/conversations/${created.id.value}/feedback") {
            withTestAuth("Z999999")
            contentType(ContentType.Application.Json)
            setBody(ConversationFeedback(liked = true))
        }

        assertEquals(HttpStatusCode.Created, feedbackResponse.status)
        val feedback = feedbackResponse.body<ConversationFeedback>()
        assertEquals(true, feedback.liked)
    }

    @Test
    fun `POST conversation feedback should accept thumbs down`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/conversations") {
            withTestAuth("Z101010")
            contentType(ContentType.Application.Json)
            setBody(NewConversation(
                title = "Negative Feedback Test",
                initialMessage = null
            ))
        }
        val created = createResponse.body<Conversation>()

        val feedbackResponse = client.post("/api/v1/conversations/${created.id.value}/feedback") {
            withTestAuth("Z101010")
            contentType(ContentType.Application.Json)
            setBody(ConversationFeedback(liked = false))
        }

        assertEquals(HttpStatusCode.Created, feedbackResponse.status)
        val feedback = feedbackResponse.body<ConversationFeedback>()
        assertEquals(false, feedback.liked)
    }

    @Test
    fun `conversations should be isolated per user`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // User 1 creates conversations
        repeat(2) { i ->
            client.post("/api/v1/conversations") {
                withTestAuth("Z111111")
                contentType(ContentType.Application.Json)
                setBody(NewConversation(
                    title = "User1 Conversation $i",
                    initialMessage = null
                ))
            }
        }

        // User 2 creates conversations
        repeat(3) { i ->
            client.post("/api/v1/conversations") {
                withTestAuth("Z222222")
                contentType(ContentType.Application.Json)
                setBody(NewConversation(
                    title = "User2 Conversation $i",
                    initialMessage = null
                ))
            }
        }

        // Verify User 1 only sees their conversations
        val user1Response = client.get("/api/v1/conversations") {
            withTestAuth("Z111111")
            contentType(ContentType.Application.Json)
        }
        val user1Conversations = user1Response.body<List<Conversation>>()
        assertTrue(user1Conversations.all { it.title.startsWith("User1") })

        // Verify User 2 only sees their conversations
        val user2Response = client.get("/api/v1/conversations") {
            withTestAuth("Z222222")
            contentType(ContentType.Application.Json)
        }
        val user2Conversations = user2Response.body<List<Conversation>>()
        assertTrue(user2Conversations.all { it.title.startsWith("User2") })
    }
}
