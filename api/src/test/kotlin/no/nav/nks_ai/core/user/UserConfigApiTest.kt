package no.nav.nks_ai.core.user

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.nks_ai.api.core.user.UserConfig
import no.nav.nks_ai.api.core.user.UserConfigDto
import no.nav.nks_ai.api.core.user.UserType
import no.nav.nks_ai.api.core.user.PatchUserConfig
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integrasjonstester for user config-API.
 *
 * Tester GET /api/v1/user/config, PUT og PATCH.
 * Bruker NAVident-claim fra JWT-token til å identifisere brukeren — samme
 * mekanisme som i produksjon.
 */
class UserConfigApiTest {

    // ─── GET /user/config ────────────────────────────────────────────────────

    @Test
    fun `GET user-config - krever autentisering`() = testApp { client ->
        client.get("/api/v1/user/config").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET user-config - oppretter config med defaultverdier for ny bruker`() = testApp { client ->
        val nyttToken = TestOAuth2Server.tokenFor("GET_DEFAULT_${System.nanoTime()}")

        client.get("/api/v1/user/config") {
            bearerAuth(nyttToken)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val config = body<UserConfigDto>()
            assertTrue(config.showStartInfo)
            assertTrue(config.showTutorial)
            assertFalse(config.showNewConceptInfo)
            assertEquals(UserType.User, config.userType)
        }
    }

    @Test
    fun `GET user-config - returnerer samme config ved gjentatte kall`() = testApp { client ->
        val token = TestOAuth2Server.userToken()

        val first = client.get("/api/v1/user/config") {
            bearerAuth(token)
        }.body<UserConfigDto>()

        val second = client.get("/api/v1/user/config") {
            bearerAuth(token)
        }.body<UserConfigDto>()

        assertEquals(first, second)
    }

    @Test
    fun `GET user-config - returnerer userType Admin for admin-bruker`() = testApp { client ->
        client.get("/api/v1/user/config") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val config = body<UserConfigDto>()
            assertEquals(UserType.Admin, config.userType)
        }
    }

    @Test
    fun `GET user-config - ulike brukere faar separate configs`() = testApp { client ->
        val brukerToken = TestOAuth2Server.tokenFor("ISOLERT_C_${System.nanoTime()}")
        val adminToken = TestOAuth2Server.adminTokenFor("ISOLERT_D_${System.nanoTime()}")

        // Bruker C endrer sin config
        client.get("/api/v1/user/config") { bearerAuth(brukerToken) }
        client.put("/api/v1/user/config") {
            bearerAuth(brukerToken)
            contentType(ContentType.Application.Json)
            setBody(UserConfig(showStartInfo = false, showTutorial = false, showNewConceptInfo = true))
        }

        // Bruker D (admin) skal fortsatt ha defaultverdier
        val adminConfig = client.get("/api/v1/user/config") {
            bearerAuth(adminToken)
        }.body<UserConfigDto>()

        assertTrue(adminConfig.showStartInfo)
        assertTrue(adminConfig.showTutorial)
        assertFalse(adminConfig.showNewConceptInfo)
    }

    // ─── PUT /user/config ────────────────────────────────────────────────────

    @Test
    fun `PUT user-config - krever autentisering`() = testApp { client ->
        client.put("/api/v1/user/config") {
            contentType(ContentType.Application.Json)
            setBody(UserConfig(showStartInfo = false, showTutorial = false, showNewConceptInfo = false))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PUT user-config - oppdaterer alle felter`() = testApp { client ->
        val token = TestOAuth2Server.userToken()

        // Sørg for at brukeren eksisterer
        client.get("/api/v1/user/config") { bearerAuth(token) }

        client.put("/api/v1/user/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UserConfig(showStartInfo = false, showTutorial = false, showNewConceptInfo = true))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<UserConfigDto>()
            assertFalse(updated.showStartInfo)
            assertFalse(updated.showTutorial)
            assertTrue(updated.showNewConceptInfo)
        }
    }

    @Test
    fun `PUT user-config - endringen persisteres`() = testApp { client ->
        val token = TestOAuth2Server.userToken()

        // Sørg for at brukeren eksisterer
        client.get("/api/v1/user/config") { bearerAuth(token) }

        client.put("/api/v1/user/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UserConfig(showStartInfo = false, showTutorial = true, showNewConceptInfo = true))
        }

        // Hent på nytt og verifiser at verdiene er lagret
        val fetched = client.get("/api/v1/user/config") {
            bearerAuth(token)
        }.body<UserConfigDto>()

        assertFalse(fetched.showStartInfo)
        assertTrue(fetched.showTutorial)
        assertTrue(fetched.showNewConceptInfo)
    }

    @Test
    fun `PUT user-config - returnerer 404 naar brukeren ikke eksisterer enda`() = testApp { client ->
        // Bruker som garantert ikke har eksistert i databasen fra andre tester
        val nyttToken = TestOAuth2Server.tokenFor("PUT_404_${System.nanoTime()}")

        client.put("/api/v1/user/config") {
            bearerAuth(nyttToken)
            contentType(ContentType.Application.Json)
            setBody(UserConfig(showStartInfo = false, showTutorial = false, showNewConceptInfo = false))
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    // ─── PATCH /user/config ──────────────────────────────────────────────────

    @Test
    fun `PATCH user-config - krever autentisering`() = testApp { client ->
        client.patch("/api/v1/user/config") {
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig(showStartInfo = false))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PATCH user-config - oppdaterer kun angitte felter`() = testApp { client ->
        // Unik bruker slik at vi vet nøyaktig hvilke defaultverdier som gjelder
        val token = TestOAuth2Server.tokenFor("PATCH_ISOLERT_${System.nanoTime()}")

        // Opprett config med kjente defaultverdier
        client.get("/api/v1/user/config") { bearerAuth(token) }

        // Patch bare showStartInfo
        client.patch("/api/v1/user/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig(showStartInfo = false))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val patched = body<UserConfigDto>()
            assertFalse(patched.showStartInfo)
            // showTutorial og showNewConceptInfo skal være uendret (default)
            assertTrue(patched.showTutorial)
            assertFalse(patched.showNewConceptInfo)
        }
    }

    @Test
    fun `PATCH user-config - tom patch endrer ingenting`() = testApp { client ->
        val token = TestOAuth2Server.userToken()

        val original = client.get("/api/v1/user/config") {
            bearerAuth(token)
        }.body<UserConfigDto>()

        client.patch("/api/v1/user/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val afterPatch = body<UserConfigDto>()
            assertEquals(original.showStartInfo, afterPatch.showStartInfo)
            assertEquals(original.showTutorial, afterPatch.showTutorial)
            assertEquals(original.showNewConceptInfo, afterPatch.showNewConceptInfo)
        }
    }

    @Test
    fun `PATCH user-config - endringen persisteres`() = testApp { client ->
        val token = TestOAuth2Server.userToken()

        // Opprett config
        client.get("/api/v1/user/config") { bearerAuth(token) }

        client.patch("/api/v1/user/config") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig(showNewConceptInfo = true))
        }

        val fetched = client.get("/api/v1/user/config") {
            bearerAuth(token)
        }.body<UserConfigDto>()

        assertTrue(fetched.showNewConceptInfo)
    }

    @Test
    fun `PATCH user-config - returnerer 404 naar brukeren ikke eksisterer`() = testApp { client ->
        // Bruker som garantert ikke har eksistert i databasen fra andre tester
        val nyttToken = TestOAuth2Server.tokenFor("PATCH_404_${System.nanoTime()}")

        client.patch("/api/v1/user/config") {
            bearerAuth(nyttToken)
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig(showStartInfo = false))
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }
}
