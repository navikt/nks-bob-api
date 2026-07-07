package no.nav.nks_ai.delete_conversations

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tester for delete-conversations-jobben.
 *
 * Bruker testConfigOverride for å bootstrappe main() direkte.
 * WireMock stubber både Texas token-endepunktet og bob-api-endepunktet —
 * all HTTP-trafikk fra jobben går mot én WireMock-instans på dynamisk port.
 */
class DeleteConversationsJobTest {

    private lateinit var wireMock: WireMockServer

    @BeforeTest
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
    }

    @AfterTest
    fun tearDown() {
        wireMock.stop()
        testConfigOverride = null
    }

    // ─── Hjelpemetoder ───────────────────────────────────────────────────────

    private val texasToken = "test-texas-token"

    private fun configureTest(texasStatusCode: HttpStatusCode = HttpStatusCode.OK) {
        val apiBaseUrl = "http://localhost:${wireMock.port()}"

        wireMock.stubFor(
            post(urlEqualTo("/texas/api/v1/token"))
                .willReturn(
                    if (texasStatusCode.isSuccess()) {
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """{"access_token":"$texasToken","expires_in":3599,"token_type":"Bearer"}"""
                            )
                    } else {
                        aResponse()
                            .withStatus(texasStatusCode.value)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"error":"unauthorized"}""")
                    }
                )
        )

        testConfigOverride = Config(
            api = ApiConfig(url = apiBaseUrl, scope = "api://test-scope"),
            jwt = JwtConfig(
                clientId = "test-client-id",
                clientSecret = "test-client-secret",
                configTokenEndpoint = "$apiBaseUrl/entraid/token",
            ),
            nais = NaisConfig(tokenEndpoint = "$apiBaseUrl/texas/api/v1/token"),
        )
    }

    private fun stubApiSuccess(deletedConversations: Int = 3, deletedMessages: Int = 12) {
        wireMock.stubFor(
            post(urlEqualTo("/api/v1/admin/jobs/delete-old-conversations"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"deletedConversations":$deletedConversations,"deletedMessages":$deletedMessages}"""
                        )
                )
        )
    }

    private fun stubApiError(
        status: Int,
        code: Int = status,
        message: String = "Error",
        description: String = "Something went wrong",
    ) {
        wireMock.stubFor(
            post(urlEqualTo("/api/v1/admin/jobs/delete-old-conversations"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":$code,"message":"$message","description":"$description"}""")
                )
        )
    }

    // ─── Vellykket kjøring ───────────────────────────────────────────────────

    @Test
    fun `main - kjorer uten feil ved vellykket API-respons`() = runBlocking {
        configureTest()
        stubApiSuccess(deletedConversations = 5, deletedMessages = 20)

        main()
    }

    @Test
    fun `main - kjorer uten feil naar ingen data slettes`() = runBlocking {
        configureTest()
        stubApiSuccess(deletedConversations = 0, deletedMessages = 0)

        main()
    }

    @Test
    fun `main - sender riktig Authorization-header til API-et`() = runBlocking {
        configureTest()
        stubApiSuccess()

        main()

        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/v1/admin/jobs/delete-old-conversations"))
                .withHeader(HttpHeaders.Authorization, equalTo("Bearer $texasToken"))
        )
    }

    @Test
    fun `main - kaller endepunktet noyaktig en gang`() = runBlocking {
        configureTest()
        stubApiSuccess()

        main()

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/v1/admin/jobs/delete-old-conversations")))
    }

    // ─── Feilhåndtering: API-feil ────────────────────────────────────────────

    @Test
    fun `main - kaster IllegalStateException ved 500 fra API`() = runBlocking {
        configureTest()
        stubApiError(status = 500, message = "Internal Server Error", description = "Database nede")

        val ex = assertFailsWith<IllegalStateException> { main() }

        assertTrue(ex.message!!.contains("500"), "Forventet statuskode 500 i feilmelding: ${ex.message}")
    }

    @Test
    fun `main - kaster IllegalStateException ved 401 fra API`() = runBlocking {
        configureTest()
        stubApiError(status = 401, message = "Unauthorized", description = "Ugyldig token")

        val ex = assertFailsWith<IllegalStateException> { main() }

        assertTrue(ex.message!!.contains("401"), "Forventet statuskode 401 i feilmelding: ${ex.message}")
    }

    @Test
    fun `main - feilmelding inneholder API-feilmelding`() = runBlocking {
        configureTest()
        stubApiError(status = 500, message = "Service utilgjengelig", description = "Databasen svarer ikke")

        val ex = assertFailsWith<IllegalStateException> { main() }

        assertTrue(
            ex.message!!.contains("Service utilgjengelig"),
            "Forventet API-feilmelding i exception: ${ex.message}"
        )
    }

    // ─── Feilhåndtering: Texas-token feiler ─────────────────────────────────

    @Test
    fun `main - kaster IllegalStateException naar Texas returnerer 401`() = runBlocking {
        configureTest(texasStatusCode = HttpStatusCode.Unauthorized)

        val ex = assertFailsWith<IllegalStateException> { main() }

        assertTrue(
            ex.message!!.contains("Could not fetch machine token"),
            "Forventet token-feilmelding: ${ex.message}"
        )
    }

    @Test
    fun `main - kaller ikke API-et naar Texas-token feiler`() = runBlocking {
        configureTest(texasStatusCode = HttpStatusCode.Unauthorized)

        assertFailsWith<IllegalStateException> { main() }

        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/v1/admin/jobs/delete-old-conversations")))
    }
}
