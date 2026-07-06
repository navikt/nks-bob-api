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
 *   - KBS v2 stream         POST /api/v2/stream/chat
 *
 * Bruk [stubTexasToken] for å registrere et token for en spesifikk audience.
 * Bruk [stubKbsStream] for å registrere et SSE-svar fra KBS.
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

    /**
     * Legger til en stub for KBS v2 SSE-strøm.
     * Returnerer [sseBody] som `text/event-stream` på `POST /api/v2/stream/chat`.
     *
     * Kall denne med innholdet fra en av testfilene i mocks/wiremock/__files/v2/.
     */
    fun stubKbsStream(sseBody: String) {
        server.stubFor(
            post(urlEqualTo("/api/v2/stream/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withHeader("Cache-Control", "no-cache")
                        .withBody(sseBody)
                )
        )
    }

    /**
     * Legger til en stub for KBS v2 som returnerer en feil-event.
     */
    fun stubKbsStreamError(
        type: String = "urn:nks-kbs:error:model",
        status: Int = 500,
        title: String = "Modellen er utilgjengelig",
        detail: String = "Intern feil i språkmodellen",
    ) {
        val errorJson = """{"type":"$type","status":$status,"title":"$title","detail":"$detail"}"""
        stubKbsStream("event: error\ndata: $errorJson\n\n")
    }

    /** Fjerner alle registrerte stubs — nyttig mellom tester ved behov. */
    fun resetStubs() {
        server.resetAll()
    }
}
