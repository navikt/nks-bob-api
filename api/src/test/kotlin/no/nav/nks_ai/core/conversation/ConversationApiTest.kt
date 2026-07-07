package no.nav.nks_ai.core.conversation

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationFeedback
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.conversation.UpdateConversation
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for conversation-API.
 *
 * Dekker CRUD for conversations, meldingshistorikk og feedback.
 * Streaming (SSE/WebSocket) testes ikke her.
 *
 * POST /{id}/messages starter KBS-kall i bakgrunnen (fire-and-forget) —
 * endepunktet returnerer 202 Accepted umiddelbart, og vi verifiserer kun
 * at spørsmålet er persistert i databasen etterpå.
 */
class ConversationApiTest {

    // ─── GET /conversations ──────────────────────────────────────────────────

    @Test
    fun `GET conversations - krever autentisering`() = testApp { client ->
        client.get("/api/v1/conversations").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET conversations - returnerer tom liste for ny bruker`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100001")

        client.get("/api/v1/conversations") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val conversations = body<List<Conversation>>()
            assertTrue(conversations.isEmpty())
        }
    }

    @Test
    fun `GET conversations - returnerer opprettet conversation`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100002")

        client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Test", initialMessage = null))
        }

        val conversations = client.get("/api/v1/conversations") {
            bearerAuth(token)
        }.body<List<Conversation>>()

        assertEquals(1, conversations.size)
        assertEquals("Test", conversations.first().title)
    }

    @Test
    fun `GET conversations - bruker ser ikke andres samtaler`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("E100003")
        val tokenB = TestOAuth2Server.tokenFor("E100004")

        // Bruker A oppretter en samtale
        client.post("/api/v1/conversations") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Bruker A sin samtale", initialMessage = null))
        }

        // Bruker B skal ikke se den
        val conversationsB = client.get("/api/v1/conversations") {
            bearerAuth(tokenB)
        }.body<List<Conversation>>()

        assertTrue(conversationsB.none { it.title == "Bruker A sin samtale" })
    }

    // ─── POST /conversations ─────────────────────────────────────────────────

    @Test
    fun `POST conversations - krever autentisering`() = testApp { client ->
        client.post("/api/v1/conversations") {
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Ulovlig", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations - oppretter conversation uten initialMessage`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100005")

        client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Ny samtale", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val created = body<Conversation>()
            assertNotNull(created.id)
            assertEquals("Ny samtale", created.title)
            assertNotNull(created.createdAt)
        }
    }

    @Test
    fun `POST conversations - kutter tittel til 255 tegn`() = testApp { client ->
        val langTittel = "A".repeat(300)

        client.post("/api/v1/conversations") {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = langTittel, initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val created = body<Conversation>()
            assertTrue(created.title.length <= 255)
        }
    }

    @Test
    fun `POST conversations - oppretter conversation med initialMessage`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100006")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                NewConversation(
                    title = "Samtale med spørsmål",
                    initialMessage = NewMessage("Hva er AAP?"),
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
        }.body<Conversation>()

        // Spørsmålet skal være lagret i meldingshistorikken
        // (KBS-svaret er fire-and-forget og ikke garantert å ha kommet)
        val messages = client.get("/api/v1/conversations/${created.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        assertTrue(messages.any { it.content == "Hva er AAP?" })
    }

    // ─── GET /conversations/{id} ─────────────────────────────────────────────

    @Test
    fun `GET conversations-id - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/conversations/$unknownId").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET conversations-id - returnerer conversation`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100007")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Hent meg", initialMessage = null))
        }.body<Conversation>()

        client.get("/api/v1/conversations/${created.id.value}") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val fetched = body<Conversation>()
            assertEquals(created.id, fetched.id)
            assertEquals("Hent meg", fetched.title)
        }
    }

    @Test
    fun `GET conversations-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/conversations/$unknownId") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `GET conversations-id - returnerer 404 for annens conversation`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("E100008")
        val tokenB = TestOAuth2Server.tokenFor("E100009")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Privat", initialMessage = null))
        }.body<Conversation>()

        client.get("/api/v1/conversations/${created.id.value}") {
            bearerAuth(tokenB)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── PUT /conversations/{id} ─────────────────────────────────────────────

    @Test
    fun `PUT conversations-id - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.put("/api/v1/conversations/$unknownId") {
            contentType(ContentType.Application.Json)
            setBody(UpdateConversation(title = "Ny tittel"))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PUT conversations-id - oppdaterer tittel`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100010")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Gammel tittel", initialMessage = null))
        }.body<Conversation>()

        client.put("/api/v1/conversations/${created.id.value}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateConversation(title = "Ny tittel"))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<Conversation>()
            assertEquals("Ny tittel", updated.title)
            assertEquals(created.id, updated.id)
        }
    }

    @Test
    fun `PUT conversations-id - returnerer 404 for annens conversation`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("E100011")
        val tokenB = TestOAuth2Server.tokenFor("E100012")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Privat", initialMessage = null))
        }.body<Conversation>()

        client.put("/api/v1/conversations/${created.id.value}") {
            bearerAuth(tokenB)
            contentType(ContentType.Application.Json)
            setBody(UpdateConversation(title = "Hacket tittel"))
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── DELETE /conversations/{id} ──────────────────────────────────────────

    @Test
    fun `DELETE conversations-id - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.delete("/api/v1/conversations/$unknownId").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `DELETE conversations-id - sletter conversation`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100013")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Skal slettes", initialMessage = null))
        }.body<Conversation>()

        client.delete("/api/v1/conversations/${created.id.value}") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.NoContent, status)
        }

        client.get("/api/v1/conversations/${created.id.value}") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `DELETE conversations-id - returnerer 404 for annens conversation`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("E100014")
        val tokenB = TestOAuth2Server.tokenFor("E100015")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Privat", initialMessage = null))
        }.body<Conversation>()

        client.delete("/api/v1/conversations/${created.id.value}") {
            bearerAuth(tokenB)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── GET /conversations/{id}/messages ────────────────────────────────────

    @Test
    fun `GET conversations-id-messages - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/conversations/$unknownId/messages").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET conversations-id-messages - returnerer tom liste for ny conversation`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100016")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Tom samtale", initialMessage = null))
        }.body<Conversation>()

        client.get("/api/v1/conversations/${created.id.value}/messages") {
            bearerAuth(token)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val messages = body<List<Message>>()
            assertTrue(messages.isEmpty())
        }
    }

    @Test
    fun `GET conversations-id-messages - returnerer 404 for annens conversation`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("E100017")
        val tokenB = TestOAuth2Server.tokenFor("E100018")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Privat", initialMessage = null))
        }.body<Conversation>()

        client.get("/api/v1/conversations/${created.id.value}/messages") {
            bearerAuth(tokenB)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── POST /conversations/{id}/messages ───────────────────────────────────

    @Test
    fun `POST conversations-id-messages - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.post("/api/v1/conversations/$unknownId/messages") {
            contentType(ContentType.Application.Json)
            setBody(NewMessage("Spørsmål"))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations-id-messages - returnerer 202 Accepted`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100019")

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Send melding", initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage("Hva er dagpenger?"))
        }.apply {
            assertEquals(HttpStatusCode.Accepted, status)
        }
    }

    @Test
    fun `POST conversations-id-messages - sporsmaalet lagres i databasen`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100020")

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Persist test", initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage("Hva er sykepenger?"))
        }

        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        assertTrue(messages.any { it.content == "Hva er sykepenger?" })
    }

    // ─── POST /conversations/{id}/feedback ───────────────────────────────────

    @Test
    fun `POST conversations-id-feedback - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.post("/api/v1/conversations/$unknownId/feedback") {
            contentType(ContentType.Application.Json)
            setBody(ConversationFeedback(liked = true))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations-id-feedback - registrerer positiv tilbakemelding`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100021")

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Feedback test", initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${conversation.id.value}/feedback") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ConversationFeedback(liked = true))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val feedback = body<ConversationFeedback>()
            assertEquals(true, feedback.liked)
        }
    }

    @Test
    fun `POST conversations-id-feedback - registrerer negativ tilbakemelding`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("E100022")

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Negativ feedback", initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${conversation.id.value}/feedback") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ConversationFeedback(liked = false))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val feedback = body<ConversationFeedback>()
            assertEquals(false, feedback.liked)
        }
    }

    @Test
    fun `POST conversations-id-feedback - returnerer 404 for annens conversation`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("E100023")
        val tokenB = TestOAuth2Server.tokenFor("E100024")

        val created = client.post("/api/v1/conversations") {
            bearerAuth(tokenA)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Privat", initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${created.id.value}/feedback") {
            bearerAuth(tokenB)
            contentType(ContentType.Application.Json)
            setBody(ConversationFeedback(liked = true))
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }
}
