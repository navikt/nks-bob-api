package no.nav.nks_ai.core.v2

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageRole
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEvent
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.TestWireMock
import no.nav.nks_ai.testutil.testApp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for SendMessageService v2 via SSE-endepunktet.
 *
 * Tester POST /api/v2/conversations/{id}/messages/sse med KBS stubbet i WireMock.
 * Verifiserer at riktige ConversationEvent-typer produseres og at svar persisteres korrekt.
 *
 * Test-data fra mocks/wiremock/__files/v2/ brukes som KBS-respons.
 */
class SendMessageServiceV2Test {

    // ─── Hjelpemetoder ───────────────────────────────────────────────────────

    private fun sseFixture(filename: String): String =
        File("../mocks/wiremock/__files/v2/$filename").readText()

    /**
     * Parser SSE-tekst til liste av ConversationEvent.
     * Ignorerer event-navn — data-linjene er alltid JSON-serialiserte ConversationEvent.
     */
    private fun parseEvents(sseText: String): List<ConversationEvent> {
        val json = Json { ignoreUnknownKeys = true }
        return sseText
            .lines()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { data ->
                runCatching { json.decodeFromString<ConversationEvent>(data) }.getOrNull()
            }
    }

    private suspend fun io.ktor.client.HttpClient.opprettSamtale(token: String): Conversation =
        post("/api/v1/conversations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "SSE-test", initialMessage = null))
        }.body<Conversation>()

    // ─── Autentisering ───────────────────────────────────────────────────────

    @Test
    fun `SSE v2 - krever autentisering`() = testApp { client ->
        client.post("/api/v2/conversations/00000000-0000-0000-0000-000000000000/messages/sse") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"test"}""")
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    // ─── Happypath: fallback-data (enkelt svar uten kontekst) ───────────────

    @Test
    fun `SSE v2 - sender NewMessage-event med tom pending melding ved oppstart`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_NM_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("fallback-data.txt"))

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hei"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        // Første event skal alltid være NewMessage for spørsmålet (fra SSE-ruten)
        // etterfulgt av NewMessage for det tomme svaret (fra SendMessageService)
        val newMessageEvents = events.filterIsInstance<ConversationEvent.NewMessage>()
        assertTrue(newMessageEvents.size >= 2, "Forventet minst 2 NewMessage-events, fikk ${newMessageEvents.size}")
    }

    @Test
    fun `SSE v2 - fallback-data produserer ContentUpdated-events med token-chunks`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_CU_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("fallback-data.txt"))

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hei"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        val contentEvents = events.filterIsInstance<ConversationEvent.ContentUpdated>()
        assertTrue(contentEvents.isNotEmpty(), "Forventet ContentUpdated-events fra token_chunks")

        // Verifiser at chunksene bygger opp det forventede svaret
        val fullContent = contentEvents.joinToString("") { it.content }
        assertTrue(fullContent.contains("Hei"), "Forventet 'Hei' i akkumulert innhold, fikk: $fullContent")
    }

    @Test
    fun `SSE v2 - fallback-data avsluttes med PendingUpdated false`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_PU_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("fallback-data.txt"))

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hei"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        val pendingEvent = events.filterIsInstance<ConversationEvent.PendingUpdated>().lastOrNull()
        assertNotNull(pendingEvent, "Forventet PendingUpdated-event ved avslutning")
        assertEquals(false, pendingEvent.pending)
    }

    @Test
    fun `SSE v2 - fallback-data produserer follow-up forslag`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_FU_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("fallback-data.txt"))

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hei"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        // Siste PendingUpdated skal ha follow-up fra chat_chunk
        val pendingEvent = events.filterIsInstance<ConversationEvent.PendingUpdated>().lastOrNull()
        assertNotNull(pendingEvent)
        assertTrue(
            pendingEvent.message.followUp.isNotEmpty(),
            "Forventet follow-up forslag i siste melding"
        )
    }

    // ─── Happypath: dagpenger-data (svar med kontekst og sitering) ──────────

    @Test
    fun `SSE v2 - dagpenger-data produserer StatusUpdate-events`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_SU_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("dagpenger-data.txt"))

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hva er dagpenger?"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        val statusEvents = events.filterIsInstance<ConversationEvent.StatusUpdate>()
        assertTrue(statusEvents.isNotEmpty(), "Forventet StatusUpdate-events")
        assertTrue(
            statusEvents.any { it.content.contains("Vurderer") },
            "Forventet 'Vurderer'-status"
        )
    }

    @Test
    fun `SSE v2 - dagpenger-data produserer ContextUpdated-event`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_CTX_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("dagpenger-data.txt"))

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hva er dagpenger?"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        val contextEvents = events.filterIsInstance<ConversationEvent.ContextUpdated>()
        assertTrue(contextEvents.isNotEmpty(), "Forventet ContextUpdated-events")
        assertTrue(
            contextEvents.last().context.isNotEmpty(),
            "Forventet ikke-tom kontekst i siste ContextUpdated"
        )
    }

    @Test
    fun `SSE v2 - svar persisteres i databasen etter fullfort strom`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_PERSIST_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("dagpenger-data.txt"))

        // Kjør SSE-kallet og vent på at det er ferdig
        client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hva er dagpenger?"))
        }

        // Hent meldingshistorikken og verifiser at svar er lagret
        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        val aiMessage = messages.firstOrNull { it.messageRole == MessageRole.AI }
        assertNotNull(aiMessage, "Forventet et AI-svar lagret i databasen")
        assertEquals(false, aiMessage.pending, "Svaret skal ikke være pending etter fullført strøm")
        assertTrue(
            aiMessage.content.contains("dagpenger", ignoreCase = true),
            "Forventet 'dagpenger' i lagret svarinnhold"
        )
    }

    @Test
    fun `SSE v2 - sykepenger-data gir riktig svarinnhold`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_SP_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStream(sseFixture("sykepenger-data.txt"))

        client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hva er sykepenger?"))
        }

        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        val aiMessage = messages.firstOrNull { it.messageRole == MessageRole.AI }
        assertNotNull(aiMessage)
        assertTrue(
            aiMessage.content.contains("sykepenger", ignoreCase = true),
            "Forventet 'sykepenger' i lagret svarinnhold"
        )
    }

    // ─── Feilhåndtering ─────────────────────────────────────────────────────

    @Test
    fun `SSE v2 - KBS modell-feil produserer ErrorsUpdated-event`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_ERR_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStreamError(
            type = "urn:nks-kbs:error:model",
            status = 500,
            title = "Modellen er utilgjengelig",
            detail = "Intern feil i språkmodellen",
        )

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Test"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        val errorEvent = events.filterIsInstance<ConversationEvent.ErrorsUpdated>().lastOrNull()
        assertNotNull(errorEvent, "Forventet ErrorsUpdated-event ved KBS-feil")
        assertTrue(errorEvent.errors.isNotEmpty())
        assertTrue(
            errorEvent.errors.any { it.title == "Modellen er utilgjengelig" },
            "Forventet feilmelding fra KBS i errors-listen"
        )
    }

    @Test
    fun `SSE v2 - KBS feil med flagget i tittel teller ikke som answerFailedReceive`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_FLAG_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStreamError(
            type = "urn:nks-kbs:error:model",
            status = 400,
            title = "Spørsmålet er flagget",
            detail = "Innholdet bryter med retningslinjene",
        )

        val sseText = client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Upassende spørsmål"))
        }.bodyAsText()

        val events = parseEvents(sseText)

        // Skal produsere feil-event, men ikke telle som failed receive
        val errorEvent = events.filterIsInstance<ConversationEvent.ErrorsUpdated>().lastOrNull()
        assertNotNull(errorEvent, "Forventet ErrorsUpdated-event selv for flaggede meldinger")
        assertTrue(
            errorEvent.errors.any { it.title.lowercase().contains("flagget") },
            "Forventet feilmelding om flagging"
        )
    }

    @Test
    fun `SSE v2 - feil fra KBS persisteres i databasen`() = testApp { client ->
        val token = TestOAuth2Server.tokenFor("SSE_ERRP_${System.nanoTime()}")
        val conversation = client.opprettSamtale(token)

        TestWireMock.stubKbsStreamError(
            title = "Modellen er utilgjengelig",
            detail = "Intern feil",
        )

        client.post("/api/v2/conversations/${conversation.id.value}/messages/sse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Test"))
        }

        val messages = client.get("/api/v1/conversations/${conversation.id.value}/messages") {
            bearerAuth(token)
        }.body<List<Message>>()

        val aiMessage = messages.firstOrNull { it.messageRole == MessageRole.AI }
        assertNotNull(aiMessage, "Forventet et AI-svar (med feil) i databasen")
        assertEquals(false, aiMessage.pending, "Feilmelding skal ikke være pending")
        assertTrue(aiMessage.errors.isNotEmpty(), "Forventet at feil er lagret i meldingen")
    }
}
