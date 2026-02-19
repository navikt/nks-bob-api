package no.nav.nks_ai.integration

import arrow.core.getOrElse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import no.nav.nks_ai.app.Page
import no.nav.nks_ai.core.conversation.ConversationRepo
import no.nav.nks_ai.core.conversation.NewConversation
import no.nav.nks_ai.core.feedback.CreateFeedback
import no.nav.nks_ai.core.feedback.Domain
import no.nav.nks_ai.core.feedback.Feedback
import no.nav.nks_ai.core.feedback.ResolvedCategory
import no.nav.nks_ai.core.feedback.ResolvedImportance
import no.nav.nks_ai.core.feedback.UpdateFeedback
import no.nav.nks_ai.core.message.MessageRepo
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.MessageType
import no.nav.nks_ai.core.user.NavIdent

/**
 * Integration tests for Feedback API.
 * Tests the full stack from HTTP API through service layer to database.
 */
class FeedbackIntegrationTest : ApiIntegrationTestBase() {

    private val feedbackTestNavIdent = NavIdent("Z888888")

    private suspend fun createTestConversationAndMessage() =
        ConversationRepo.addConversation(
            feedbackTestNavIdent,
            NewConversation("Test Conversation", null)
        ).getOrElse { throw AssertionError("Failed to create test conversation") }
            .let { conversation ->
                MessageRepo.addMessage(
                    conversationId = conversation.id,
                    messageContent = "Test message for feedback",
                    messageType = MessageType.Answer,
                    messageRole = MessageRole.AI,
                    createdBy = "assistant",
                    context = emptyList(),
                    citations = emptyList(),
                    pending = false
                ).getOrElse { throw AssertionError("Failed to create test message") }
            }

    @Test
    fun `addFeedback should create new feedback in database`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        val response = client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("hele-deler-av-svaret-er-feil", "mangler-vesentlige-detaljer"),
                    comment = "This answer is not accurate"
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val feedback = response.body<Feedback>()
        assertEquals(message.id, feedback.messageId)
        assertEquals(2, feedback.options.size)
        assertEquals("This answer is not accurate", feedback.comment)
    }

    @Test
    fun `getFeedbackById should retrieve specific feedback`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        val createResponse = client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "Other feedback"
                )
            )
        }
        val created = createResponse.body<Feedback>()

        val response = client.get("/api/v1/admin/feedbacks/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<Feedback>()
        assertEquals(created.id, feedback.id)
        assertEquals("Other feedback", feedback.comment)
    }

    @Test
    fun `getFeedbackById should return error for non-existent id`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val fakeId = java.util.UUID.randomUUID()
        val response = client.get("/api/v1/admin/feedbacks/$fakeId") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `getFeedbacksByMessageId should return all feedbacks for message`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("hele-deler-av-svaret-er-feil"),
                    comment = "First feedback"
                )
            )
        }

        client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("mangler-vesentlige-detaljer"),
                    comment = "Second feedback"
                )
            )
        }

        val response = client.get("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedbacks = response.body<List<Feedback>>()
        assertTrue(feedbacks.size >= 2)
    }

    @Test
    fun `getFeedbacks should return paginated results`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        // Create multiple feedbacks
        repeat(3) { i ->
            client.post("/api/v1/messages/${message.id.value}/feedback") {
                withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
                setBody(
                    CreateFeedback(
                        options = listOf("annet"),
                        comment = "Feedback $i"
                    )
                )
            }
        }

        val response = client.get("/api/v1/admin/feedbacks?page=0&size=2") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.body<Page<Feedback>>()
        assertTrue(page.data.size <= 2)
        assertTrue(page.total >= 3)
    }

    @Test
    fun `updateFeedback should update all fields`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        val createResponse = client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "Original comment"
                )
            )
        }
        val created = createResponse.body<Feedback>()

        val updateResponse = client.put("/api/v1/admin/feedbacks/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = listOf("hele-deler-av-svaret-er-feil"),
                    comment = "Updated comment",
                    resolved = true,
                    resolvedImportance = ResolvedImportance.Important,
                    resolvedCategory = ResolvedCategory.AiError,
                    resolvedNote = "This was an AI error",
                    domain = Domain.Helse
                )
            )
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = updateResponse.body<Feedback>()
        assertEquals("Updated comment", updated.comment)
        assertEquals(true, updated.resolved)
        assertEquals(ResolvedImportance.Important, updated.resolvedImportance)
        assertEquals(ResolvedCategory.AiError, updated.resolvedCategory)
        assertEquals("This was an AI error", updated.resolvedNote)
        assertEquals(Domain.Helse, updated.domain)
    }

    @Test
    fun `deleteFeedback should remove feedback from database`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        val createResponse = client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "To be deleted"
                )
            )
        }
        val created = createResponse.body<Feedback>()

        val deleteResponse = client.delete("/api/v1/admin/feedbacks/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val getResponse = client.get("/api/v1/admin/feedbacks/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `getFeedbacksFilteredBy should filter by unresolved status`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val message = runBlocking { createTestConversationAndMessage() }

        // Create resolved feedback
        val resolvedResponse = client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "Resolved feedback"
                )
            )
        }
        val resolved = resolvedResponse.body<Feedback>()

        client.put("/api/v1/admin/feedbacks/${resolved.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = resolved.options,
                    comment = resolved.comment,
                    resolved = true,
                    resolvedImportance = null,
                    resolvedCategory = null,
                    resolvedNote = null,
                    domain = null
                )
            )
        }

        // Create unresolved feedback
        client.post("/api/v1/messages/${message.id.value}/feedback") {
            withTestAuth("Z888888")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "Unresolved feedback"
                )
            )
        }

        val response = client.get("/api/v1/admin/feedbacks?filter=nye") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.body<Page<Feedback>>()
        assertTrue(page.data.all { !it.resolved })
    }
}
