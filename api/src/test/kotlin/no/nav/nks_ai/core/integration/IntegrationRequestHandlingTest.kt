package no.nav.nks_ai.core.integration

import arrow.core.left
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationPageRequest
import no.nav.nks_ai.api.core.integration.AcceptedIntegrationMessage
import no.nav.nks_ai.api.core.integration.IntegrationConversationService
import no.nav.nks_ai.api.core.integration.IntegrationProblem
import no.nav.nks_ai.api.core.integration.integrationConversationRoutes
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.core.user.NavIdent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegrationRequestHandlingTest {
    private val root = "/api/v1/integrations/users/A123456/conversations"

    @Test
    fun `malformed JSON returns a generic IntegrationProblem`() = withService(StubService()) { client ->
        client.post(root) {
            contentType(ContentType.Application.Json)
            setBody("{")
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val problem = body<IntegrationProblem>()
            assertEquals("Malformed request body", problem.detail)
            assertEquals(400, problem.status)
        }
    }

    @Test
    fun `unsupported content type returns an IntegrationProblem`() = withService(StubService()) { client ->
        client.post(root) {
            contentType(ContentType.Text.Plain)
            setBody("not json")
        }.apply {
            assertEquals(HttpStatusCode.UnsupportedMediaType, status)
            val problem = body<IntegrationProblem>()
            assertEquals("Content-Type must be application/json", problem.detail)
            assertEquals(415, problem.status)
        }
    }

    @Test
    fun `unexpected integration error does not expose exception details`() {
        withService(StubService(failure = IllegalStateException("private request data"))) { client ->
            client.post("$root/${UUID.randomUUID()}/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"Hello"}""")
            }.apply {
                assertEquals(HttpStatusCode.InternalServerError, status)
                val problem = body<IntegrationProblem>()
                assertEquals("An unexpected error occurred", problem.detail)
                assertEquals(500, problem.status)
            }
        }
    }

    @Test
    fun `persisted message processing failure returns conflict and ids`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val messageId = MessageId(UUID.randomUUID())
        val service = StubService(
            messageResult = ApplicationError.MessageProcessingNotStarted(conversationId, messageId).left(),
        )

        withService(service) { client ->
            client.post("$root/${conversationId.value}/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"Hello"}""")
            }.apply {
                assertEquals(HttpStatusCode.Conflict, status)
                val problem = body<IntegrationProblem>()
                assertEquals(409, problem.status)
                assertEquals(conversationId, problem.conversationId)
                assertEquals(messageId, problem.messageId)
                assertEquals("urn:nks-bob-api:message-processing-not-started", problem.type)
                assertEquals(
                    "The message was saved, but processing did not start. Do not retry this request.",
                    problem.detail,
                )
            }
        }
    }

    private fun withService(
        service: IntegrationConversationService,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) {
                json()
            }
            routing {
                integrationConversationRoutes(service)
            }
        }
        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }
        block(client)
    }

    private class StubService(
        private val messageResult: ApplicationResult<AcceptedIntegrationMessage>? = null,
        private val failure: Exception? = null,
    ) : IntegrationConversationService {
        override suspend fun createConversation(
            owner: NavIdent,
            conversation: NewConversation,
        ): ApplicationResult<Conversation> = error("Not used by this test")

        override suspend fun createMessage(
            owner: NavIdent,
            conversationId: ConversationId,
            message: NewMessage,
        ): ApplicationResult<AcceptedIntegrationMessage> =
            failure?.let { throw it } ?: checkNotNull(messageResult) { "Unexpected createMessage call" }

        override suspend fun getActiveConversations(
            owner: NavIdent,
            request: ActiveConversationPageRequest,
        ): ApplicationResult<Page<ActiveConversation>> = error("Not used by this test")

        override suspend fun shutdown() = Unit
    }
}
