package no.nav.nks_ai.testutil

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

/**
 * Singleton WireMock-server for integrasjonstester.
 *
 * Startes én gang per JVM-prosess og deles på tvers av alle testklasser.
 * Setter opp stubs for eksterne HTTP-tjenester som applikasjonen kaller:
 *
 *   - Texas token-endpoint  POST /api/v1/token
 *
 * Bruk [stubTexasToken] for å registrere et token for en spesifikk audience.
 * Bruk [resetStubs] for å fjerne alle stubs mellom tester ved behov.
 */
object TestWireMock {
    val server: WireMockServer by lazy {
        WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
    }

    val baseUrl: String get() = "http://localhost:${server.port()}"
    val tokenEndpoint: String get() = "$baseUrl/api/v1/token"

    /**
     * Legger til en stub som returnerer et dummy access token for en gitt audience.
     * Matcher på `target`-feltet i request-body.
     */
    fun stubTexasToken(
        audience: String,
        accessToken: String = "test-machine-token-for-$audience",
    ) {
        server.stubFor(
            post(urlEqualTo("/api/v1/token"))
                .withRequestBody(
                    equalToJson(
                        """{"target": "$audience", "identity_provider": "entra_id"}""",
                        /* ignoreArrayOrder = */ true,
                        /* ignoreExtraElements = */ false,
                    )
                )
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "access_token": "$accessToken",
                              "expires_in": 3599,
                              "token_type": "Bearer"
                            }
                            """.trimIndent()
                        )
                )
        )
    }

    /** Fjerner alle registrerte stubs — nyttig mellom tester ved behov. */
    fun resetStubs() {
        server.resetAll()
    }
}
