package no.nav.nks_ai.core.integration

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.integration.IntegrationConversationRequest
import no.nav.nks_ai.api.core.integration.IntegrationProblem
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID

/**
 * Integrasjonstester for integrasjons-API-et som eksterne partnere (f.eks. Salesforce) bruker
 * for å opprette samtaler/meldinger på vegne av en veileder, se `IntegrationConversationService`.
 *
 * Endepunktene krever authenticate("Maskinporten") og er IKKE ment å gis reell tilgang til
 * Salesforce ennå (se README) — disse testene verifiserer kun at koden fungerer med et gyldig
 * Maskinporten-token utstedt lokalt av mock-oauth2-server.
 */
class IntegrationApiTest {

    private val root = "/api/v1/integrations/conversations"

    // ─── Autentisering ───────────────────────────────────────────────────────

    @Test
    fun `POST conversations - krever autentisering`() = testApp { client ->
        client.post(root) {
            contentType(ContentType.Application.Json)
            setBody(
                IntegrationConversationRequest(TestOAuth2Server.NAV_IDENT, title = "Test", initialMessage = null)
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations - avviser vanlig brukertoken`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(
                IntegrationConversationRequest(TestOAuth2Server.NAV_IDENT, title = "Test", initialMessage = null)
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations - avviser token uten påkrevd scope`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken(scope = "nav:annet/scope"))
            contentType(ContentType.Application.Json)
            setBody(
                IntegrationConversationRequest(TestOAuth2Server.NAV_IDENT, title = "Test", initialMessage = null)
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST conversations - avviser tidligere scope med bindestrek`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken(scope = "nav:nks-bob-api/integrations"))
            contentType(ContentType.Application.Json)
            setBody(
                IntegrationConversationRequest(TestOAuth2Server.NAV_IDENT, title = "Test", initialMessage = null)
            )
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
            setBody(
                IntegrationConversationRequest(
                    TestOAuth2Server.NAV_IDENT, title = "Integrasjonstest", initialMessage = null,
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Created, status)
            val conversation = body<Conversation>()
            assertEquals("Integrasjonstest", conversation.title)
        }
    }

    @Test
    fun `POST conversations - ugyldig mottaker i body gir 400`() = testApp { client ->
        for (navIdent in listOf("", " ", "not-an-ident")) {
            client.post(root) {
                bearerAuth(TestOAuth2Server.maskinportenToken())
                contentType(ContentType.Application.Json)
                setBody(IntegrationConversationRequest(navIdent, title = "Test", initialMessage = null))
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
                assertEquals("Invalid Nav-ident format", body<IntegrationProblem>().detail)
            }
        }
    }

    @Test
    fun `POST conversations - tidligere mottakersti er fjernet`() = testApp { client ->
        client.post("/api/v1/integrations/users/${TestOAuth2Server.NAV_IDENT}/conversations") {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
            setBody(
                IntegrationConversationRequest(TestOAuth2Server.NAV_IDENT, title = "Test", initialMessage = null)
            )
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `POST conversations active - is not registered`() = testApp { client ->
        client.post("$root/active") {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `POST conversation messages - is not registered`() = testApp { client ->
        client.post("$root/${UUID.randomUUID()}/messages") {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Application.Json)
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `POST conversations - ugyldig JSON gir sanitert integrasjonsfeil`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken())
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
    fun `POST conversations - feil innholdstype gir integrasjonsfeil`() = testApp { client ->
        client.post(root) {
            bearerAuth(TestOAuth2Server.maskinportenToken())
            contentType(ContentType.Text.Plain)
            setBody("not json")
        }.apply {
            assertEquals(HttpStatusCode.UnsupportedMediaType, status)
            val problem = body<IntegrationProblem>()
            assertEquals("Content-Type must be application/json", problem.detail)
            assertEquals(415, problem.status)
        }
    }
}
