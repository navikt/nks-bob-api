package no.nav.nks_ai.core.jobs

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.ignoredWords.IgnoredWord
import no.nav.nks_ai.api.core.ignoredWords.NewIgnoredWord
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageRole
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.core.message.UpdateMessage
import no.nav.nks_ai.shared.DeleteIgnoredWordsSummary
import no.nav.nks_ai.shared.DeleteOldConversationsSummary
import no.nav.nks_ai.shared.UploadStarredMessagesSummary
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import no.nav.nks_ai.testutil.testAppWithBigQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrasjonstester for jobs-API.
 *
 * Tester POST /admin/jobs/delete-old-conversations,
 *             /admin/jobs/upload-starred-messages og
 *             /admin/jobs/delete-ignored-words.
 *
 * Endepunktene krever MachineToken (idtyp=app + azp-claim).
 *
 * Merk: deleteOldConversations og deleteIgnoredWords sletter kun data
 * eldre enn 30 dager — i disse testene verifiserer vi at ny data ikke
 * slettes, og at jobben returnerer 0 slettede når ingen kvalifiserer.
 */
class JobsApiTest {

    // ─── Autentisering ───────────────────────────────────────────────────────

    @Test
    fun `POST delete-old-conversations - krever autentisering`() = testApp { client ->
        client.post("/api/v1/admin/jobs/delete-old-conversations").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST upload-starred-messages - krever autentisering`() = testApp { client ->
        client.post("/api/v1/admin/jobs/upload-starred-messages").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST delete-ignored-words - krever autentisering`() = testApp { client ->
        client.post("/api/v1/admin/jobs/delete-ignored-words").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST delete-old-conversations - avviser bruker-token`() = testApp { client ->
        client.post("/api/v1/admin/jobs/delete-old-conversations") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST delete-old-conversations - avviser admin-token`() = testApp { client ->
        client.post("/api/v1/admin/jobs/delete-old-conversations") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    // ─── delete-old-conversations ────────────────────────────────────────────

    @Test
    fun `POST delete-old-conversations - returnerer 200 med tom summary naar ingen data kvalifiserer`() =
        testApp { client ->
            val summary = client.post("/api/v1/admin/jobs/delete-old-conversations") {
                bearerAuth(TestOAuth2Server.machineToken())
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<DeleteOldConversationsSummary>()

            assertEquals(0, summary.deletedConversations)
            assertEquals(0, summary.deletedMessages)
        }

    @Test
    fun `POST delete-old-conversations - sletter ikke samtaler yngre enn 30 dager`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("JOBS_DEL_CONV_${System.nanoTime()}")
        val machineToken = TestOAuth2Server.machineToken()

        // Opprett en samtale
        client.post("/api/v1/conversations") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Skal ikke slettes", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
        }

        // Kjør jobb — ingenting skal slettes siden samtalen er ny
        val summary = client.post("/api/v1/admin/jobs/delete-old-conversations") {
            bearerAuth(machineToken)
        }.body<DeleteOldConversationsSummary>()

        assertEquals(0, summary.deletedConversations)
        assertEquals(0, summary.deletedMessages)

        // Verifiser at samtalen fortsatt eksisterer
        val conversations = client.get("/api/v1/conversations") {
            bearerAuth(userToken)
        }.body<List<Conversation>>()

        assertTrue(conversations.isNotEmpty())
    }

    // ─── upload-starred-messages ─────────────────────────────────────────────

    @Test
    fun `POST upload-starred-messages - returnerer 200 med tom summary naar ingen stjernemerkede meldinger finnes`() =
        testApp { client ->
            // Tøm eventuelle stjernemerkede meldinger fra andre tester ved å kjøre jobben først
            client.post("/api/v1/admin/jobs/upload-starred-messages") {
                bearerAuth(TestOAuth2Server.machineToken())
            }

            // Nå skal det ikke være noen igjen (alle ble enten lastet opp eller har feil)
            val summary = client.post("/api/v1/admin/jobs/upload-starred-messages") {
                bearerAuth(TestOAuth2Server.machineToken())
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<UploadStarredMessagesSummary>()

            assertEquals(0, summary.uploadedMessages)
            assertTrue(summary.errors.isEmpty())
        }

    @Test
    fun `POST upload-starred-messages - meldinger uten stjerne lastes ikke opp`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("JOBS_STAR_${System.nanoTime()}")
        val machineToken = TestOAuth2Server.machineToken()

        // Tell stjernemerkede meldinger som allerede finnes i databasen fra andre tester
        val baselineSummary = client.post("/api/v1/admin/jobs/upload-starred-messages") {
            bearerAuth(machineToken)
        }.body<UploadStarredMessagesSummary>()

        // Opprett samtale og legg til en melding uten stjerne
        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Test", initialMessage = null))
        }.body<Conversation>()

        client.post("/api/v1/conversations/${conversation.id}/messages") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Spørsmål uten stjerne"))
        }

        // Kjør jobben på nytt — antall opplastede skal ikke ha økt
        val summary = client.post("/api/v1/admin/jobs/upload-starred-messages") {
            bearerAuth(machineToken)
        }.body<UploadStarredMessagesSummary>()

        assertEquals(0, summary.uploadedMessages, "Ustjernede meldinger skal ikke lastes opp")
        assertEquals(baselineSummary.errors.size, summary.errors.size, "Antall feil skal ikke ha økt")
    }

    @Test
    fun `POST upload-starred-messages - stjernemerket melding plukkes opp av jobb`() = testAppWithBigQuery { client, bigQuery ->
        val userToken = TestOAuth2Server.tokenFor("JOBS_STAR2_${System.nanoTime()}")
        val machineToken = TestOAuth2Server.machineToken()

        val conversation = client.post("/api/v1/conversations") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Svar-test", initialMessage = NewMessage("Test")))
        }.body<Conversation>()

        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(userToken)
        }.body<List<Message>>()

        val message = messages.first { it.messageRole == MessageRole.AI }

        // Stjernemerk meldingen
        client.put("/api/v1/messages/${message.id.value}") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateMessage(id = message.id, starred = true))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        // Jobb skal plukke opp den stjernemerkede meldingen og sende den til BigQuery
        val summary = client.post("/api/v1/admin/jobs/upload-starred-messages") {
            bearerAuth(machineToken)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<UploadStarredMessagesSummary>()

        assertEquals(1, summary.uploadedMessages)
        assertTrue(summary.errors.isEmpty())

        // Verifiser at BigQuery faktisk mottok insert-kallet med riktig tabell
        assertEquals(1, bigQuery.insertCalls.size)
        assertEquals("testgrunnlag", bigQuery.insertCalls[0].dataset)
        assertEquals("stjernemarkerte_svar_local", bigQuery.insertCalls[0].table)
    }

    // ─── delete-ignored-words ────────────────────────────────────────────────

    @Test
    fun `POST delete-ignored-words - returnerer 200 med tom summary naar ingen data kvalifiserer`() =
        testApp { client ->
            val summary = client.post("/api/v1/admin/jobs/delete-ignored-words") {
                bearerAuth(TestOAuth2Server.machineToken())
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }.body<DeleteIgnoredWordsSummary>()

            assertEquals(0, summary.deletedWords)
        }

    @Test
    fun `POST delete-ignored-words - sletter ikke ord yngre enn 30 dager`() = testApp { client ->
        val userToken = TestOAuth2Server.tokenFor("JOBS_IGN_${System.nanoTime()}")
        val machineToken = TestOAuth2Server.machineToken()

        // Opprett et ignorert ord
        client.post("/api/v1/ignored-words") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(NewIgnoredWord(value = "testord", validationType = "Spelling", conversationId = null))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
        }.body<IgnoredWord>()

        // Kjør jobb — ingenting skal slettes siden ordet er nytt
        val summary = client.post("/api/v1/admin/jobs/delete-ignored-words") {
            bearerAuth(machineToken)
        }.body<DeleteIgnoredWordsSummary>()

        assertEquals(0, summary.deletedWords)
    }
}
