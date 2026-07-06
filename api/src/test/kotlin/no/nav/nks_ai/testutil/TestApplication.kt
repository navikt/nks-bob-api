package no.nav.nks_ai.testutil

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.nks_ai.api.app.BigQueryConfig
import no.nav.nks_ai.api.app.Config
import no.nav.nks_ai.api.app.DbConfig
import no.nav.nks_ai.api.app.IssuerConfig
import no.nav.nks_ai.api.app.JwtConfig
import no.nav.nks_ai.api.app.KbsConfig
import no.nav.nks_ai.api.app.MetricsConfig
import no.nav.nks_ai.api.app.NaisConfig
import no.nav.nks_ai.api.app.UnleashSettings
import no.nav.nks_ai.api.app.VaskemaskinConfig
import no.nav.nks_ai.api.app.testConfigOverride
import no.nav.nks_ai.api.module
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback

/**
 * Singleton for mock-OAuth2-server. Deles på tvers av alle testklasser.
 */
object TestOAuth2Server {
    val server: MockOAuth2Server by lazy {
        MockOAuth2Server().apply { start() }
    }

    const val ISSUER_ID = "test-issuer"
    const val AUDIENCE = "test-audience"
    const val ADMIN_GROUP = "test-admin-group"
    const val NAV_IDENT = "A123456"
    const val ADMIN_NAV_IDENT = "B654321"

    /** Utsteder et standard bruker-token (ingen admin-gruppe). */
    fun userToken(): String = tokenFor(NAV_IDENT)

    /** Utsteder et admin-token med riktig admin-gruppe. */
    fun adminToken(): String = adminTokenFor(ADMIN_NAV_IDENT)

    /** Utsteder et bruker-token for et spesifikt NAVident — nyttig for å skille brukere i tester. */
    fun tokenFor(navIdent: String): String = server.issueToken(
        issuerId = ISSUER_ID,
        clientId = "test-client",
        tokenCallback = DefaultOAuth2TokenCallback(
            issuerId = ISSUER_ID,
            subject = navIdent,
            audience = listOf(AUDIENCE),
            claims = mapOf("NAVident" to navIdent),
        )
    ).serialize()

    /** Utsteder et admin-token for et spesifikt NAVident. */
    fun adminTokenFor(navIdent: String): String = server.issueToken(
        issuerId = ISSUER_ID,
        clientId = "test-client",
        tokenCallback = DefaultOAuth2TokenCallback(
            issuerId = ISSUER_ID,
            subject = navIdent,
            audience = listOf(AUDIENCE),
            claims = mapOf(
                "NAVident" to navIdent,
                "groups" to listOf(ADMIN_GROUP),
            ),
        )
    ).serialize()

    /**
     * Utsteder et maskin-til-maskin-token (MachineToken) med idtyp=app og azp-claim.
     * Brukes av jobs-endepunkter som krever authenticate("MachineToken").
     */
    fun machineToken(azp: String = "test-job-client"): String = server.issueToken(
        issuerId = ISSUER_ID,
        clientId = azp,
        tokenCallback = DefaultOAuth2TokenCallback(
            issuerId = ISSUER_ID,
            subject = azp,
            audience = listOf(AUDIENCE),
            claims = mapOf(
                "idtyp" to "app",
                "azp" to azp,
            ),
        )
    ).serialize()
}

/**
 * Starter en Ktor-testapplikasjon med:
 *  - Ekte PostgreSQL via Testcontainers (Flyway-migrasjoner kjøres automatisk)
 *  - mock-oauth2-server som JWT-utsteder
 *  - WireMock som stub for Texas token-endpoint og andre eksterne HTTP-tjenester
 *  - Alle produksjonsplugins og ruter aktive
 *
 * Bygger en fullstendig Config-instans direkte fra de kjørende test-serverne og
 * setter den som testConfigOverride i getConfig() — slik at appConfig sin lazy-cache
 * aldri påvirker testene.
 */
fun testApp(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) {
    val oauth = TestOAuth2Server.server
    val db = TestDatabase
    val wireMock = TestWireMock

    // Issuer-URL fra mock-oauth2-server — denne brukes som `iss`-claim i tokens
    // og må matche nøyaktig det JwkProviderBuilder verifiserer mot.
    val issuerUrl = oauth.issuerUrl(TestOAuth2Server.ISSUER_ID).toString()

    // Sett opp Texas-stubs for alle audiences som applikasjonen kan be om token for.
    wireMock.stubTexasToken(audience = "scope") // vaskemaskin + kbs scope fra test-konfig

    testConfigOverride = Config(
        kbs = KbsConfig(url = wireMock.baseUrl, scope = "scope"),
        vaskemaskin = VaskemaskinConfig(url = wireMock.baseUrl, scope = "scope"),
        jwt = JwtConfig(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            configTokenEndpoint = "http://localhost:9090/token",
            adminGroup = TestOAuth2Server.ADMIN_GROUP,
        ),
        db = DbConfig(
            username = db.container.username,
            password = db.container.password,
            database = db.container.databaseName,
            host = "localhost",
            port = db.container.firstMappedPort.toString(),
            jdbcURL = db.jdbcUrl,
        ),
        nais = NaisConfig(
            electorUrl = "",
            appName = "",
            tokenEndpoint = wireMock.tokenEndpoint,
        ),
        issuer = IssuerConfig(
            issuer_name = issuerUrl,
            discoveryurl = oauth.wellKnownUrl(TestOAuth2Server.ISSUER_ID).toString(),
            jwksurl = oauth.jwksUrl(TestOAuth2Server.ISSUER_ID).toString(),
            accepted_audience = TestOAuth2Server.AUDIENCE,
        ),
        bigQuery = BigQueryConfig(
            projectId = "local",
            kunnskapsbaseDataset = "kunnskapsbase",
            kunnskapsartiklerTable = "kunnskapsartikler",
            testgrunnlagDataset = "testgrunnlag",
            stjernemarkerteSvarTable = "stjernemarkerte_svar_local",
        ),
        unleash = UnleashSettings(serverApiUrl = "", serverApiToken = "", appName = "nks-bob-api-test"),
        metrics = MetricsConfig(navIdentSecret = "test-secret"),
    )

    try {
        testApplication {
            application {
                module()
            }

            val httpClient = createClient {
                install(ContentNegotiation) {
                    json()
                }
            }

            block(httpClient)
        }
    } finally {
        testConfigOverride = null
    }
}

