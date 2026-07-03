package no.nav.nks_ai.core.feedback

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.feedback.CreateFeedback
import no.nav.nks_ai.api.core.feedback.Feedback
import no.nav.nks_ai.api.core.feedback.ResolvedCategory
import no.nav.nks_ai.api.core.feedback.ResolvedImportance
import no.nav.nks_ai.api.core.feedback.UpdateFeedback
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageRole
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for feedback admin-API.
 *
 * Feedback-endepunktene under /api/v1/admin/feedbacks krever AdminUser-token.
 * Feedback opprettes via bruker-API-et (POST /messages/{id}/feedback) og
 * leses/oppdateres/slettes av admin.
 *
 * Rutene som testes:
 *   GET    /admin/feedbacks            — paginert liste, med filtrering
 *   GET    /admin/feedbacks/{id}       — enkelt oppslag
 *   PUT    /admin/feedbacks/{id}       — oppdater (ferdigstill)
 *   DELETE /admin/feedbacks/{id}       — slett
 *   PUT    /admin/feedbacks_batch      — batch-ferdigstill
 */
class FeedbackApiTest {

    // ─── Hjelpemetoder ───────────────────────────────────────────────────────

    /**
     * Oppretter en AI-melding som brukeren kan gi feedback på.
     * Bruker-API-et krever en answer-melding — vi legger den direkte
     * inn via POST /conversations/{id}/messages (spørsmål) og henter
     * den automatisk opprettede svar-stubben fra meldingshistorikken.
     *
     * Siden KBS ikke er tilgjengelig, legger vi heller spørsmålet manuelt
     * og lar conversation/messages-endepunktet returnere det for videre bruk.
     * For feedback trenger vi en Answer-melding, så vi oppretter en via
     * initialMessage på conversation POST som trigger addEmptyAnswer().
     */
    private suspend fun ApplicationTestBuilder.opprettSvarMelding(
        token: String,
    ): Pair<Conversation, Message> {
        val client = createClient { install(ContentNegotiation) { json() } }

        // Opprett conversation med initialMessage — trigger addEmptyAnswer i bakgrunnen
        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Feedback-test", initialMessage = NewMessage("Hva er AAP?")))
        }.body<Conversation>()

        // Vent på at den tomme svar-meldingen er persistert
        // (fire-and-forget, men skjer synkront innen DB-transaksjonen)
        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        // Bruk første AI-melding (tom answer persistert av addEmptyAnswer)
        val answer = messages.firstOrNull { it.messageRole == MessageRole.AI }
            ?: error("Ingen AI-melding funnet etter opprettelse av conversation med initialMessage")

        return conversation to answer
    }

    /** Oppretter feedback fra bruker på en answer-melding. */
    private suspend fun ApplicationTestBuilder.opprettFeedback(
        userToken: String,
        options: List<String> = listOf("Annet"),
        comment: String? = "Test-kommentar",
    ): Feedback {
        val client = createClient { install(ContentNegotiation) { json() } }
        val (_, answer) = opprettSvarMelding(userToken)

        return client.post("/api/v1/messages/${answer.id.value}/feedback") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(CreateFeedback(options = options, comment = comment))
        }.body<Feedback>()
    }

    // ─── GET /admin/feedbacks ────────────────────────────────────────────────

    @Test
    fun `GET admin-feedbacks - krever admin-token`() = testApp { client ->
        client.get("/api/v1/admin/feedbacks") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-feedbacks - krever autentisering`() = testApp { client ->
        client.get("/api/v1/admin/feedbacks").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-feedbacks - returnerer side med feedbacks`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100001")
        opprettFeedback(userToken)

        client.get("/api/v1/admin/feedbacks") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val page = body<Page<Feedback>>()
            assertNotNull(page)
            assertTrue(page.total >= 1)
        }
    }

    @Test
    fun `GET admin-feedbacks - filtrerer paa uloest`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100002")
        val feedback = opprettFeedback(userToken)

        // Ny feedback er uløst per default
        val page = client.get("/api/v1/admin/feedbacks?filter=nye") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<Page<Feedback>>()

        assertTrue(page.data.any { it.id == feedback.id })
        assertTrue(page.data.all { !it.resolved })
    }

    @Test
    fun `GET admin-feedbacks - filtrerer paa loest`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100003")
        val feedback = opprettFeedback(userToken)
        val adminToken = TestOAuth2Server.adminToken()

        // Ferdigstill feedback
        client.put("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = feedback.options,
                    comment = feedback.comment,
                    resolved = true,
                    resolvedImportance = ResolvedImportance.NotRelevant,
                    resolvedCategory = null,
                    resolvedNote = "Ferdigstilt i test",
                    domain = null,
                )
            )
        }

        val page = client.get("/api/v1/admin/feedbacks?filter=ferdigstilte") {
            bearerAuth(adminToken)
        }.body<Page<Feedback>>()

        assertTrue(page.data.any { it.id == feedback.id })
        assertTrue(page.data.all { it.resolved })
    }

    // ─── GET /admin/feedbacks/{id} ───────────────────────────────────────────

    @Test
    fun `GET admin-feedbacks-id - krever admin-token`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/admin/feedbacks/$unknownId") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET admin-feedbacks-id - returnerer feedback`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100004")
        val feedback = opprettFeedback(userToken, options = listOf("Annet"), comment = "En kommentar")

        client.get("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val fetched = body<Feedback>()
            assertEquals(feedback.id, fetched.id)
            assertEquals(listOf("Annet"), fetched.options)
            assertEquals("En kommentar", fetched.comment)
            assertFalse(fetched.resolved)
            assertNull(fetched.resolvedImportance)
            assertNull(fetched.resolvedCategory)
        }
    }

    @Test
    fun `GET admin-feedbacks-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/admin/feedbacks/$unknownId") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── PUT /admin/feedbacks/{id} ───────────────────────────────────────────

    @Test
    fun `PUT admin-feedbacks-id - krever admin-token`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100005")
        val feedback = opprettFeedback(userToken)

        client.put("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = feedback.options,
                    comment = feedback.comment,
                    resolved = true,
                    resolvedImportance = null,
                    resolvedCategory = null,
                    resolvedNote = null,
                    domain = null,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PUT admin-feedbacks-id - ferdigstiller feedback med importance`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100006")
        val feedback = opprettFeedback(userToken)
        val adminToken = TestOAuth2Server.adminToken()

        client.put("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = listOf("Annet"),
                    comment = "Oppdatert kommentar",
                    resolved = true,
                    resolvedImportance = ResolvedImportance.Important,
                    resolvedCategory = null,
                    resolvedNote = "Gjennomgått",
                    domain = null,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<Feedback>()
            assertTrue(updated.resolved)
            assertEquals(ResolvedImportance.Important, updated.resolvedImportance)
            assertEquals("Gjennomgått", updated.resolvedNote)
            assertEquals("Oppdatert kommentar", updated.comment)
        }
    }

    @Test
    fun `PUT admin-feedbacks-id - ferdigstiller feedback med category`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100007")
        val feedback = opprettFeedback(userToken)

        client.put("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = feedback.options,
                    comment = feedback.comment,
                    resolved = true,
                    resolvedImportance = null,
                    resolvedCategory = ResolvedCategory.UserError,
                    resolvedNote = null,
                    domain = null,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<Feedback>()
            assertTrue(updated.resolved)
            assertEquals(ResolvedCategory.UserError, updated.resolvedCategory)
        }
    }

    @Test
    fun `PUT admin-feedbacks-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.put("/api/v1/admin/feedbacks/$unknownId") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                UpdateFeedback(
                    options = listOf("Annet"),
                    comment = null,
                    resolved = true,
                    resolvedImportance = null,
                    resolvedCategory = null,
                    resolvedNote = null,
                    domain = null,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── DELETE /admin/feedbacks/{id} ────────────────────────────────────────

    @Test
    fun `DELETE admin-feedbacks-id - krever admin-token`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100008")
        val feedback = opprettFeedback(userToken)

        client.delete("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `DELETE admin-feedbacks-id - sletter feedback`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100009")
        val feedback = opprettFeedback(userToken)
        val adminToken = TestOAuth2Server.adminToken()

        client.delete("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(adminToken)
        }.apply {
            assertEquals(HttpStatusCode.NoContent, status)
        }

        client.get("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(adminToken)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `DELETE admin-feedbacks-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.delete("/api/v1/admin/feedbacks/$unknownId") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── PUT /admin/feedbacks_batch ──────────────────────────────────────────

    @Test
    fun `PUT admin-feedbacks_batch - krever admin-token`() = testApp { client ->
        client.put("/api/v1/admin/feedbacks_batch?before=2030-01-01T00:00:00&note=Test") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PUT admin-feedbacks_batch - mangler before returnerer 400`() = testApp { client ->
        client.put("/api/v1/admin/feedbacks_batch?note=Test") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `PUT admin-feedbacks_batch - mangler note returnerer 400`() = testApp { client ->
        client.put("/api/v1/admin/feedbacks_batch?before=2030-01-01T00:00:00") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `PUT admin-feedbacks_batch - ugyldig before-format returnerer 400`() = testApp { client ->
        client.put("/api/v1/admin/feedbacks_batch?before=ikke-en-dato&note=Test") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `PUT admin-feedbacks_batch - ferdigstiller uloeste feedbacks foer dato`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("G100010")
        val feedback = opprettFeedback(userToken)
        val adminToken = TestOAuth2Server.adminToken()

        assertFalse(feedback.resolved)

        // Batch-ferdigstill alt opprettet før langt frem i tid
        val response = client.put("/api/v1/admin/feedbacks_batch?before=2099-01-01T00:00:00&note=Automatisk+ferdigstilling") {
            bearerAuth(adminToken)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<no.nav.nks_ai.api.core.feedback.BatchResolveFeedbacksResponse>()

        assertTrue(response.updated >= 1)

        // Verifiser at feedback nå er ferdigstilt
        val updated = client.get("/api/v1/admin/feedbacks/${feedback.id.value}") {
            bearerAuth(adminToken)
        }.body<Feedback>()

        assertTrue(updated.resolved)
        assertEquals(ResolvedCategory.DateExpired, updated.resolvedCategory)
    }
}
