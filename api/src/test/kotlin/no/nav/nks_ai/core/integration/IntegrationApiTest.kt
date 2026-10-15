package no.nav.nks_ai.core.integration

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.integration.AcceptedIntegrationMessage
import no.nav.nks_ai.api.core.integration.IntegrationProblem
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integrasjonstester for integrasjons-API-et som eksterne partnere (f.eks. Salesforce) bruker
 * for å opprette samtaler/meldinger på vegne av en veileder, se `IntegrationConversationService`.
 *
 * Tester POST /api/v1/integrations/users/{navIdent}/conversations og
 *             /api/v1/integrations/users/{navIdent}/conversations/{id}/messages.
 *
 * Endepunktene krever authenticate("Maskinporten") og er IKKE ment å gis reell tilgang til
 * Salesforce ennå (se README) — disse testene verifiserer kun at koden fungerer med et gyldig
 * Maskinporten-token utstedt lokalt av mock-oauth2-server.
 */
class IntegrationApiTest {

    private val root = "/api/v1/integrations/users/${TestOAuth2Server.NAV_IDENT}/conversations"

    // ─── Autentisering ───────────────────────────────────────────────────────

    @Test
    fun `POST conversations - krever autentisering`() = testApp { client ->
        client.post(root) {
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Test", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations - avviser vanlig brukertoken`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Test", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations - avviser token uten påkrevd scope`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken(scope = "nav:annet/scope"))
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Test", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    // ─── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `POST conversations - oppretter samtale med gyldig maskinporten-token`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Integrasjonstest", initialMessage = null))
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val conversation = body<Conversation>()
            assertEquals("Integrasjonstest", conversation.title)
        }
    }

    @Test
    fun `POST messages - oppretter melding i eksisterende samtale`() = testApp { client ->
        val conversation = client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
            setBody(NewConversation(title = "Integrasjonstest", initialMessage = null))
        }.body<Conversation>()

        client.post("$root/${conversation.id.value}/messages") {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hei, dette er en test"))
        }.apply {
            assertEquals(HttpStatusCode.Accepted, status)
            val accepted = body<AcceptedIntegrationMessage>()
            assertEquals(conversation.id, accepted.conversationId)
        }
    }

    @Test
    fun `POST messages - ukjent samtale gir problem-json`() = testApp { client ->
        val randomId = "00000000-0000-0000-0000-000000000000"
        client.post("$root/$randomId/messages") {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
            setBody(NewMessage(content = "Hei"))
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
            val problem = body<IntegrationProblem>()
            assertEquals(404, problem.status)
        }
    }
}
