package no.nav.nks_ai.core.admin

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationSummary
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for admin-API.
 *
 * Alle endepunkter krever AdminUser-token og gir admin tilgang til
 * data på tvers av brukere — uten bcrypt-eierskapsjekk.
 *
 * Rutene som testes:
 *   GET /admin/conversations/{id}              — hent samtale (på tvers av brukere)
 *   GET /admin/conversations/{id}/messages     — hent meldinger
 *   GET /admin/conversations/{id}/summary      — hent oppsummering
 *   GET /admin/conversations/outdated          — antall utdaterte samtaler
 *   GET /admin/messages/{id}/conversation      — finn samtale fra meldings-ID
 *   GET /admin/messages/{id}/conversation/summary — oppsummering via meldings-ID
 *   GET /admin/messages/outdated               — antall utdaterte meldinger
 */
class AdminApiTest {

    // ─── Hjelpemetoder ───────────────────────────────────────────────────────

    /** Oppretter en conversation som eies av en gitt bruker. */
    private suspend fun ApplicationTestBuilder.opprettConversation(
        token: String,
        title: String = "Admin test-samtale",
    ): Conversation {
        val client = createClient { install(ContentNegotiation) { json() } }
        return client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = title, initialMessage = null))
        }.body<Conversation>()
    }

    /** Sender et spørsmål til en eksisterende samtale, returnerer meldingen. */
    private suspend fun ApplicationTestBuilder.sendSporsmal(
        token: String,
        conversationId: String,
        content: String = "Hva er dagpenger?",
    ): Message {
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/api/v1/conversations/$conversationId/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content))
        }
        return client.get("/api/v1/conversations/$conversationId/messages") {
            bearerAuth(token)
        }.body<List<Message>>().first()
    }

    // ─── GET /admin/conversations/{id} ───────────────────────────────────────

    @Test
    fun `GET admin-conversations-id - krever admin-token`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100001")
        val conversation = opprettConversation(userToken)

        client.get("/api/v1/admin/conversations/${conversation.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-conversations-id - krever autentisering`() = testApp { client ->
        client.get("/api/v1/admin/conversations/00000000-0000-0000-0000-000000000000").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-conversations-id - henter samtale uavhengig av eier`() = testApp { client ->
        // Samtale opprettet av bruker H100002
        val userToken = TestOAuth2Server.tokenFor("H100002")
        val conversation = opprettConversation(userToken, "Admin kan se meg")

        // Admin (med annen navident) kan hente den
        client.get("/api/v1/admin/conversations/${conversation.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val fetched = body<Conversation>()
            assertEquals(conversation.id, fetched.id)
            assertEquals("Admin kan se meg", fetched.title)
        }
    }

    @Test
    fun `GET admin-conversations-id - returnerer 404 for ukjent id`() = testApp { client ->
        client.get("/api/v1/admin/conversations/00000000-0000-0000-0000-000000000000") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── GET /admin/conversations/{id}/messages ───────────────────────────────

    @Test
    fun `GET admin-conversations-id-messages - krever admin-token`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100003")
        val conversation = opprettConversation(userToken)

        client.get("/api/v1/admin/conversations/${conversation.id.value}/messages") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-conversations-id-messages - returnerer meldinger`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100004")
        val conversation = opprettConversation(userToken)
        sendSporsmal(userToken, conversation.id.value.toString(), "Spørsmål til admin")

        client.get("/api/v1/admin/conversations/${conversation.id.value}/messages") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val messages = body<List<Message>>()
            assertTrue(messages.any { it.content == "Spørsmål til admin" })
        }
    }

    @Test
    fun `GET admin-conversations-id-messages - returnerer tom liste for ny samtale`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100005")
        val conversation = opprettConversation(userToken)

        client.get("/api/v1/admin/conversations/${conversation.id.value}/messages") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val messages = body<List<Message>>()
            assertTrue(messages.isEmpty())
        }
    }

    @Test
    fun `GET admin-conversations-id-messages - returnerer 404 for ukjent id`() = testApp { client ->
        client.get("/api/v1/admin/conversations/00000000-0000-0000-0000-000000000000/messages") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── GET /admin/conversations/{id}/summary ────────────────────────────────

    @Test
    fun `GET admin-conversations-id-summary - krever admin-token`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100006")
        val conversation = opprettConversation(userToken)

        client.get("/api/v1/admin/conversations/${conversation.id.value}/summary") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-conversations-id-summary - returnerer oppsummering med meldinger`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100007")
        val conversation = opprettConversation(userToken, "Oppsummerings-test")
        sendSporsmal(userToken, conversation.id.value.toString(), "Hva er foreldrepenger?")

        client.get("/api/v1/admin/conversations/${conversation.id.value}/summary") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val summary = body<ConversationSummary>()
            assertEquals(conversation.id, summary.id)
            assertEquals("Oppsummerings-test", summary.title)
            assertNotNull(summary.summary)
            assertTrue(summary.messages.any { it.content == "Hva er foreldrepenger?" })
        }
    }

    // ─── GET /admin/conversations/outdated ────────────────────────────────────

    @Test
    fun `GET admin-conversations-outdated - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/conversations/outdated") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-conversations-outdated - returnerer antall`() = testApp { client ->
        client.get("/api/v1/admin/conversations/outdated") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            // Svaret er en CountSummary med count-felt — verifiser at kallet lykkes
            val body = body<Map<String, Int>>()
            assertTrue(body.containsKey("count"))
            assertTrue(body["count"]!! >= 0)
        }
    }

    // ─── GET /admin/messages/{id}/conversation ────────────────────────────────

    @Test
    fun `GET admin-messages-id-conversation - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/messages/00000000-0000-0000-0000-000000000000/conversation") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-messages-id-conversation - returnerer samtale fra meldings-id`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100008")
        val conversation = opprettConversation(userToken, "Meldings-samtale")
        val message = sendSporsmal(userToken, conversation.id.value.toString())

        client.get("/api/v1/admin/messages/${message.id.value}/conversation") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val fetched = body<Conversation>()
            assertEquals(conversation.id, fetched.id)
        }
    }

    @Test
    fun `GET admin-messages-id-conversation - returnerer 404 for ukjent meldings-id`() = testApp { client ->
        client.get("/api/v1/admin/messages/00000000-0000-0000-0000-000000000000/conversation") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── GET /admin/messages/{id}/conversation/summary ────────────────────────

    @Test
    fun `GET admin-messages-id-conversation-summary - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/messages/00000000-0000-0000-0000-000000000000/conversation/summary") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-messages-id-conversation-summary - returnerer oppsummering`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("H100009")
        val conversation = opprettConversation(userToken, "Summary via melding")
        val message = sendSporsmal(userToken, conversation.id.value.toString(), "Hva er uføretrygd?")

        client.get("/api/v1/admin/messages/${message.id.value}/conversation/summary") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val summary = body<ConversationSummary>()
            assertEquals(conversation.id, summary.id)
            assertTrue(summary.messages.any { it.content == "Hva er uføretrygd?" })
        }
    }

    // ─── GET /admin/messages/outdated ─────────────────────────────────────────

    @Test
    fun `GET admin-messages-outdated - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/messages/outdated") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-messages-outdated - returnerer antall`() = testApp { client ->
        client.get("/api/v1/admin/messages/outdated") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body = body<Map<String, Int>>()
            assertTrue(body.containsKey("count"))
            assertTrue(body["count"]!! >= 0)
        }
    }
}
