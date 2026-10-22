package no.nav.nks_ai.delete_active_connections

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DeleteActiveConnectionsJobTest {
    private lateinit var server: WireMockServer

    @BeforeTest
    fun setUp() {
        server = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
        server.stubFor(
            post(urlEqualTo("/token")).willReturn(
                aResponse().withHeader("Content-Type", "application/json")
                    .withBody("""{"access_token":"test-token","expires_in":3600,"token_type":"Bearer"}""")
            )
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `returns the cleanup count and calls the API once`() = runBlocking {
        stubCleanup(200, """{"deletedConnections":7}""")
        createHttpClient().use { client ->
            assertEquals(7, deleteExpiredConnections(config(), client).deletedConnections)
        }
        server.verify(
            1, postRequestedFor(urlEqualTo(CLEANUP_PATH))
                .withHeader("Authorization", equalTo("Bearer test-token"))
        )
    }

    @Test
    fun `accepts a successful cleanup with no expired connections`() = runBlocking {
        stubCleanup(200, """{"deletedConnections":0}""")
        createHttpClient().use { client ->
            assertEquals(0, deleteExpiredConnections(config(), client).deletedConnections)
        }
    }

    @Test
    fun `API failure fails the job without leaking the response body`() = runBlocking {
        stubCleanup(500, """{"description":"sensitive response content"}""")
        createHttpClient().use { client ->
            val error = assertFailsWith<IllegalStateException> {
                deleteExpiredConnections(config(), client)
            }
            assertEquals("Expired connection cleanup failed (HTTP 500)", error.message)
            assertFalse(error.message.orEmpty().contains("sensitive"))
        }
        server.verify(1, postRequestedFor(urlEqualTo(CLEANUP_PATH)))
    }

    @Test
    fun `token failure does not invoke cleanup`() = runBlocking {
        server.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(401)))
        createHttpClient().use { client ->
            assertFailsWith<IllegalStateException> { deleteExpiredConnections(config(), client) }
        }
        server.verify(0, postRequestedFor(urlEqualTo(CLEANUP_PATH)))
    }

    private fun config() = Config(
        api = ApiConfig(server.baseUrl(), "api://test-scope"),
        nais = NaisConfig("${server.baseUrl()}/token"),
    )

    private fun stubCleanup(status: Int, body: String) {
        server.stubFor(
            post(urlEqualTo(CLEANUP_PATH)).willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)
            )
        )
    }
}
