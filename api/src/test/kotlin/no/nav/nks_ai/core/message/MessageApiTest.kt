package no.nav.nks_ai.core.message

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.feedback.CreateFeedback
import no.nav.nks_ai.api.core.feedback.Feedback
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageRole
import no.nav.nks_ai.api.core.message.MessageType
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.core.message.UpdateMessage
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for message-API.
 *
 * Tester GET /messages/{id}, PUT /messages/{id} (starring) og
 * GET/POST /messages/{id}/feedback.
 *
 * Meldinger opprettes via POST /conversations/{id}/messages
 * som en forutsetning.
 */
class MessageApiTest {

    /**
     * Hjelpefunksjon: oppretter en conversation og sender et spørsmål,
     * returnerer spørsmålsmeldingen slik den er lagret i databasen.
     */
    private suspend fun ApplicationTestBuilder.opprettSporsmal(
        token: String,
        conversationTitle: String = "Test-samtale",
        content: String = "Hva er AAP?",
    ): Pair<Conversation, Message> {
        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = conversationTitle, initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content))
        }

        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        val question = messages.first { it.messageRole == MessageRole.Human }
        return conversation to question
    }

    /**
     * Hjelpefunksjon: oppretter en conversation med initialMessage som trigger
     * addEmptyAnswer() synkront — returnerer den tomme AI-svar-meldingen.
     *
     * Dette er eneste måten å få en Answer-melding uten ekte KBS-integrasjon,
     * siden POST /conversations/{id}/messages starter KBS-kallet fire-and-forget.
     */
    private suspend fun ApplicationTestBuilder.opprettSvar(
        token: String,
        sporsmal: String = "Hva er dagpenger?",
    ): Pair<Conversation, Message> {
        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Svar-test", initialMessage = NewMessage(sporsmal)))
        }.body<Conversation>()

        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        val answer = messages.first { it.messageRole == MessageRole.AI }
        return conversation to answer
    }

    // ─── GET /messages/{id} ──────────────────────────────────────────────────

    @Test
    fun `GET messages-id - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/messages/$unknownId").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET messages-id - returnerer melding`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("F100001")
        val (_, question) = opprettSporsmal(token)

        client.get("/api/v1/messages/${question.id.value}") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val message = body<Message>()
            assertEquals(question.id, message.id)
            assertEquals(MessageType.Question, message.messageType)
            assertEquals(MessageRole.Human, message.messageRole)
            assertNotNull(message.content)
        }
    }

    @Test
    fun `GET messages-id - returnerer 403 for annens melding`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("F100002")
        val tokenB = TestOAuth2Server.tokenFor("F100003")

        val (_, question) = opprettSporsmal(tokenA)

        client.get("/api/v1/messages/${question.id.value}") {
            bearerAuth(tokenB)
        }.apply {
            assertEquals(HttpStatusCode.Forbidden, status)
        }
    }

    @Test
    fun `GET messages-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/messages/$unknownId") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── PUT /messages/{id} (starring) ───────────────────────────────────────

    @Test
    fun `PUT messages-id - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.put("/api/v1/messages/$unknownId") {
            contentType(ContentType.Application.Json)
            setBody(UpdateMessage(id = MessageId(UUID.fromString(unknownId)), starred = true))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PUT messages-id - markerer melding som stjernemarkert`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("F100004")
        val (_, question) = opprettSporsmal(token)

        assertFalse(question.starred)

        client.put("/api/v1/messages/${question.id.value}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateMessage(id = question.id, starred = true))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<Message>()
            assertTrue(updated.starred)
        }
    }

    @Test
    fun `PUT messages-id - kan fjerne stjernemarking`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("F100005")
        val (_, question) = opprettSporsmal(token)

        // Stjernemark
        client.put("/api/v1/messages/${question.id.value}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateMessage(id = question.id, starred = true))
        }

        // Fjern stjernemarking
        client.put("/api/v1/messages/${question.id.value}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateMessage(id = question.id, starred = false))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<Message>()
            assertFalse(updated.starred)
        }
    }

    @Test
    fun `PUT messages-id - returnerer 403 for annens melding`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("F100006")
        val tokenB = TestOAuth2Server.tokenFor("F100007")

        val (_, question) = opprettSporsmal(tokenA)

        client.put("/api/v1/messages/${question.id.value}") {
            bearerAuth(tokenB)
            contentType(ContentType.Application.Json)
            setBody(UpdateMessage(id = question.id, starred = true))
        }.apply {
            assertEquals(HttpStatusCode.Forbidden, status)
        }
    }

    // ─── GET /messages/{id}/feedback ─────────────────────────────────────────

    @Test
    fun `GET messages-id-feedback - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/messages/$unknownId/feedback").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET messages-id-feedback - returnerer tom liste for melding uten feedback`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("F100008")
        val (_, question) = opprettSporsmal(token)

        client.get("/api/v1/messages/${question.id.value}/feedback") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val feedbacks = body<List<Feedback>>()
            assertTrue(feedbacks.isEmpty())
        }
    }

    // ─── POST /messages/{id}/feedback ────────────────────────────────────────

    @Test
    fun `POST messages-id-feedback - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.post("/api/v1/messages/$unknownId/feedback") {
            contentType(ContentType.Application.Json)
            setBody(CreateFeedback(options = listOf("annet"), comment = null))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST messages-id-feedback - returnerer 400 naar melding er et sporsmal`() = testApp { client ->
        // Feedback kan kun opprettes for svar (AI-meldinger), ikke spørsmål.
        // FeedbackService.addFeedback validerer messageType == Answer.
        val token = TestOAuth2Server.tokenFor("F100009")
        val (_, question) = opprettSporsmal(token)

        client.post("/api/v1/messages/${question.id.value}/feedback") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "Svaret var ikke relevant",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `POST messages-id-feedback - oppretter feedback paa et svar`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("F100011")
        val (_, answer) = opprettSvar(token)

        assertEquals(MessageRole.AI, answer.messageRole)

        client.post("/api/v1/messages/${answer.id.value}/feedback") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                CreateFeedback(
                    options = listOf("annet"),
                    comment = "Svaret var ikke relevant",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val feedback = body<Feedback>()
            assertNotNull(feedback.id)
            assertTrue(feedback.options.contains("annet"))
            assertEquals("Svaret var ikke relevant", feedback.comment)
            assertFalse(feedback.resolved)
        }
    }
}
