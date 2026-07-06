package no.nav.nks_ai.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Enhetstester for TexasClient.
 *
 * Bruker Ktor MockEngine for å simulere HTTP-svar uten nettverkskall.
 * Tester:
 *  - Returnerer access token ved vellykket respons
 *  - Sender riktig request-body (target + identity_provider)
 *  - Håndterer HTTP-feilstatus (401, 500)
 *  - Håndterer nettverksfeil (exception fra engine)
 *  - Håndterer ugyldig JSON i respons
 */
class TexasClientTest {

    private val logger = KotlinLogging.logger {}

    private fun clientWith(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }

    private fun tokenResponse(accessToken: String = "test-access-token") = """
        {
          "access_token": "$accessToken",
          "expires_in": 3599,
          "token_type": "Bearer"
        }
    """.trimIndent()

    // ─── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `getMachineToken - returnerer access token ved vellykket respons`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = tokenResponse("mitt-token"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("min-audience")

        assertTrue(result.isRight())
        assertEquals("mitt-token", result.getOrNull())
    }

    @Test
    fun `getMachineToken - sender riktig target og identity_provider i request`() = runBlocking {
        var capturedBody: String? = null

        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = tokenResponse(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        client.getMachineToken("scope://min-app")

        assertTrue(capturedBody != null)
        assertTrue(capturedBody.contains("scope://min-app"), "Forventet target i body: $capturedBody")
        assertTrue(capturedBody.contains("entra_id"), "Forventet identity_provider=entra_id i body: $capturedBody")
    }

    @Test
    fun `getMachineToken - sender request til riktig endepunkt`() = runBlocking {
        var capturedUrl: String? = null

        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = tokenResponse(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        client.getMachineToken("audience")

        assertEquals("http://texas/api/v1/token", capturedUrl)
    }

    // ─── HTTP-feilstatuser ───────────────────────────────────────────────────

    @Test
    fun `getMachineToken - returnerer TexasError ved 401`() = runBlocking {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.Unauthorized)
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("audience")

        assertTrue(result.isLeft())
        val error = result.leftOrNull()!!
        assertEquals(401, error.code)
        assertEquals("Could not fetch machine token", error.message)
    }

    @Test
    fun `getMachineToken - returnerer TexasError ved 500`() = runBlocking {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("audience")

        assertTrue(result.isLeft())
        val error = result.leftOrNull()!!
        assertEquals(500, error.code)
        assertEquals("Could not fetch machine token", error.message)
    }

    @Test
    fun `getMachineToken - returnerer TexasError med riktig statuskode ved 403`() = runBlocking {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.Forbidden)
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("audience")

        assertTrue(result.isLeft())
        assertEquals(403, result.leftOrNull()!!.code)
    }

    // ─── Nettverksfeil ───────────────────────────────────────────────────────

    @Test
    fun `getMachineToken - returnerer TexasError naar nettverkskall feiler`() = runBlocking {
        val engine = MockEngine { _ ->
            throw RuntimeException("Connection refused")
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("audience")

        assertTrue(result.isLeft())
        val error = result.leftOrNull()!!
        assertEquals(500, error.code)
        assertEquals("Could not fetch machine token", error.message)
        assertTrue(error.description.contains("Connection refused"), "Forventet årsak i description: ${error.description}")
    }

    // ─── Ugyldig respons ─────────────────────────────────────────────────────

    @Test
    fun `getMachineToken - returnerer TexasError ved ugyldig JSON`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = """{ "ikke_et_token": true }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("audience")

        assertTrue(result.isLeft())
        val error = result.leftOrNull()!!
        assertEquals(500, error.code)
        assertEquals("Could not fetch machine token", error.message)
    }

    @Test
    fun `getMachineToken - returnerer TexasError ved tomt svar`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = TexasClient(
            naisTokenEndpoint = "http://texas/api/v1/token",
            httpClient = clientWith(engine),
            logger = logger,
        )

        val result = client.getMachineToken("audience")

        assertTrue(result.isLeft())
        assertEquals(500, result.leftOrNull()!!.code)
    }
}
