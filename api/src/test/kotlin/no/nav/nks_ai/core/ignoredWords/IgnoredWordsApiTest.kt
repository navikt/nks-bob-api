package no.nav.nks_ai.core.ignoredWords

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.ignoredWords.IgnoredWord
import no.nav.nks_ai.api.core.ignoredWords.IgnoredWordAggregation
import no.nav.nks_ai.api.core.ignoredWords.NewIgnoredWord
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for ignored words-API.
 *
 * Bruker-endepunkt:
 *   POST /ignored-words               — legg til et ord (krever vanlig token)
 *
 * Admin-endepunkter (krever AdminUser-token):
 *   GET    /admin/ignored-words               — paginert liste
 *   GET    /admin/ignored-words/{id}          — enkelt oppslag
 *   DELETE /admin/ignored-words/{id}          — slett
 *   GET    /admin/ignored-words/aggregate     — aggregert visning
 */
class IgnoredWordsApiTest {

    // ─── Hjelpemetoder ───────────────────────────────────────────────────────

    private suspend fun ApplicationTestBuilder.opprettConversation(token: String): Conversation {
        val client = createClient { install(ContentNegotiation) { json() } }
        return client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Ignored words test", initialMessage = null))
        }.body<Conversation>()
    }

    private suspend fun ApplicationTestBuilder.leggTilOrd(
        token: String,
        value: String = "testord",
        validationType: String = "spell-check",
        conversationId: no.nav.nks_ai.api.core.conversation.ConversationId? = null,
    ): IgnoredWord {
        val client = createClient { install(ContentNegotiation) { json() } }
        return client.post("/api/v1/ignored-words") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewIgnoredWord(value = value, validationType = validationType, conversationId = conversationId))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<IgnoredWord>()
    }

    // ─── POST /ignored-words ─────────────────────────────────────────────────

    @Test
    fun `POST ignored-words - krever autentisering`() = testApp { client ->
        client.post("/api/v1/ignored-words") {
            contentType(ContentType.Application.Json)
            setBody(NewIgnoredWord(value = "ord", validationType = "spell-check", conversationId = null))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST ignored-words - oppretter et ord uten conversation`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("I100001")

        client.post("/api/v1/ignored-words") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewIgnoredWord(value = "nav", validationType = "spell-check", conversationId = null))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val word = body<IgnoredWord>()
            assertNotNull(word.id)
            assertEquals("nav", word.value)
            assertEquals("spell-check", word.validationType)
            assertNull(word.conversationId)
        }
    }

    @Test
    fun `POST ignored-words - oppretter et ord tilknyttet en conversation`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("I100002")
        val conversation = opprettConversation(token)

        client.post("/api/v1/ignored-words") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                NewIgnoredWord(
                    value = "ytelse",
                    validationType = "spell-check",
                    conversationId = conversation.id,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val word = body<IgnoredWord>()
            assertEquals("ytelse", word.value)
            assertEquals(conversation.id, word.conversationId)
        }
    }

    @Test
    fun `POST ignored-words - returnerer 404 for conversation som ikke tilhoerer bruker`() = testApp { client ->
        val tokenA = TestOAuth2Server.tokenFor("I100003")
        val tokenB = TestOAuth2Server.tokenFor("I100004")

        // Samtale eid av bruker A
        val conversationA = opprettConversation(tokenA)

        // Bruker B forsøker å knytte et ord til bruker A sin samtale
        client.post("/api/v1/ignored-words") {
            bearerAuth(tokenB)
            contentType(ContentType.Application.Json)
            setBody(
                NewIgnoredWord(
                    value = "ord",
                    validationType = "spell-check",
                    conversationId = conversationA.id,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── GET /admin/ignored-words ────────────────────────────────────────────

    @Test
    fun `GET admin-ignored-words - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/ignored-words") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-ignored-words - krever autentisering`() = testApp { client ->
        client.get("/api/v1/admin/ignored-words").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-ignored-words - returnerer paginert liste`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("I100005")
        leggTilOrd(userToken, value = "trygd", validationType = "spell-check")

        client.get("/api/v1/admin/ignored-words") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val page = body<Page<IgnoredWord>>()
            assertNotNull(page)
            assertTrue(page.total >= 1)
        }
    }

    @Test
    fun `GET admin-ignored-words - respekterer paginering`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("I100006")
        leggTilOrd(userToken, "ord1")
        leggTilOrd(userToken, "ord2")
        leggTilOrd(userToken, "ord3")

        val page = client.get("/api/v1/admin/ignored-words?page=0&size=2") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.body<Page<IgnoredWord>>()

        assertEquals(2, page.data.size)
        assertTrue(page.total >= 3)
    }

    // ─── GET /admin/ignored-words/{id} ───────────────────────────────────────

    @Test
    fun `GET admin-ignored-words-id - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/ignored-words/00000000-0000-0000-0000-000000000000") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-ignored-words-id - returnerer ordet`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("I100007")
        val word = leggTilOrd(userToken, value = "pensjon", validationType = "grammar")

        client.get("/api/v1/admin/ignored-words/${word.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val fetched = body<IgnoredWord>()
            assertEquals(word.id, fetched.id)
            assertEquals("pensjon", fetched.value)
            assertEquals("grammar", fetched.validationType)
        }
    }

    @Test
    fun `GET admin-ignored-words-id - returnerer 404 for ukjent id`() = testApp { client ->
        client.get("/api/v1/admin/ignored-words/00000000-0000-0000-0000-000000000000") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── DELETE /admin/ignored-words/{id} ────────────────────────────────────

    @Test
    fun `DELETE admin-ignored-words-id - krever admin-token`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("I100008")
        val word = leggTilOrd(userToken)

        client.delete("/api/v1/admin/ignored-words/${word.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `DELETE admin-ignored-words-id - sletter ordet`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("I100009")
        val word = leggTilOrd(userToken, value = "skalslettes")
        val adminToken = TestOAuth2Server.adminToken()

        client.delete("/api/v1/admin/ignored-words/${word.id.value}") {
            bearerAuth(adminToken)
        }.apply {
            assertEquals(HttpStatusCode.NoContent, status)
        }

        client.get("/api/v1/admin/ignored-words/${word.id.value}") {
            bearerAuth(adminToken)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `DELETE admin-ignored-words-id - returnerer 404 for ukjent id`() = testApp { client ->
        client.delete("/api/v1/admin/ignored-words/00000000-0000-0000-0000-000000000000") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── GET /admin/ignored-words/aggregate ──────────────────────────────────

    @Test
    fun `GET admin-ignored-words-aggregate - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/ignored-words/aggregate") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-ignored-words-aggregate - returnerer aggregert liste`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("I100010")

        // Legg til samme ord to ganger
        leggTilOrd(userToken, value = "duplikat", validationType = "spell-check")
        leggTilOrd(userToken, value = "duplikat", validationType = "spell-check")

        client.get("/api/v1/admin/ignored-words/aggregate") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val page = body<Page<IgnoredWordAggregation>>()
            assertNotNull(page)
            // Finn aggregasjonen for "duplikat"
            val duplikat = page.data.find { it.value == "duplikat" && it.validationType == "spell-check" }
            assertNotNull(duplikat)
            assertTrue(duplikat.count >= 2)
        }
    }
}
