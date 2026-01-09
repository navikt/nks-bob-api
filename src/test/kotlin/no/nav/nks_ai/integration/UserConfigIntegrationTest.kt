package no.nav.nks_ai.integration

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.nks_ai.core.user.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for UserConfig API.
 * Tests the full stack from HTTP API through service layer to database.
 */
class UserConfigIntegrationTest : ApiIntegrationTestBase() {

    @Test
    fun `GET config should create config with defaults when it does not exist`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val response = client.get("/api/v1/user/config") {
            withTestAuth("Z123456")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val config = response.body<UserConfigDto>()
        // Verify default values are set
        assertTrue(config.showStartInfo)
        assertTrue(config.showTutorial)
        assertEquals(false, config.showNewConceptInfo) // Default is false
    }

    @Test
    fun `PUT config should update and retrieve config from database`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // First GET to create config with defaults
        client.get("/api/v1/user/config") {
            withTestAuth("Z789012")
            contentType(ContentType.Application.Json)
        }

        // Update config via PUT
        val putResponse = client.put("/api/v1/user/config") {
            withTestAuth("Z789012")
            contentType(ContentType.Application.Json)
            setBody(UserConfig(
                showStartInfo = false,
                showTutorial = true,
                showNewConceptInfo = false
            ))
        }
        assertEquals(HttpStatusCode.OK, putResponse.status)

        // Retrieve config via GET
        val getResponse = client.get("/api/v1/user/config") {
            withTestAuth("Z789012")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        val retrievedConfig = getResponse.body<UserConfigDto>()
        assertEquals(false, retrievedConfig.showStartInfo)
        assertEquals(true, retrievedConfig.showTutorial)
        assertEquals(false, retrievedConfig.showNewConceptInfo)
    }

    @Test
    fun `PUT config should update all fields`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // First GET to create config with defaults
        client.get("/api/v1/user/config") {
            withTestAuth("Z345678")
            contentType(ContentType.Application.Json)
        }

        // Create initial config
        client.put("/api/v1/user/config") {
            withTestAuth("Z345678")
            contentType(ContentType.Application.Json)
            setBody(UserConfig(
                showStartInfo = true,
                showTutorial = true,
                showNewConceptInfo = false
            ))
        }

        // Update config
        val updateResponse = client.put("/api/v1/user/config") {
            withTestAuth("Z345678")
            contentType(ContentType.Application.Json)
            setBody(UserConfig(
                showStartInfo = false,
                showTutorial = false,
                showNewConceptInfo = true
            ))
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updated = updateResponse.body<UserConfigDto>()
        assertEquals(false, updated.showStartInfo)
        assertEquals(false, updated.showTutorial)
        assertEquals(true, updated.showNewConceptInfo)

        // Verify persistence
        val getResponse = client.get("/api/v1/user/config") {
            withTestAuth("Z345678")
            contentType(ContentType.Application.Json)
        }
        val retrieved = getResponse.body<UserConfigDto>()
        assertEquals(false, retrieved.showStartInfo)
        assertEquals(false, retrieved.showTutorial)
        assertEquals(true, retrieved.showNewConceptInfo)
    }

    @Test
    fun `PATCH config should update only specified fields`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // First GET to create config with defaults
        client.get("/api/v1/user/config") {
            withTestAuth("Z567890")
            contentType(ContentType.Application.Json)
        }

        // Create initial config
        client.put("/api/v1/user/config") {
            withTestAuth("Z567890")
            contentType(ContentType.Application.Json)
            setBody(UserConfig(
                showStartInfo = true,
                showTutorial = true,
                showNewConceptInfo = false
            ))
        }

        // Patch only showStartInfo
        val patchResponse = client.patch("/api/v1/user/config") {
            withTestAuth("Z567890")
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig(
                showStartInfo = false,
                showTutorial = null,
                showNewConceptInfo = null
            ))
        }

        assertEquals(HttpStatusCode.OK, patchResponse.status)
        val patched = patchResponse.body<UserConfigDto>()
        assertEquals(false, patched.showStartInfo) // Changed
        assertEquals(true, patched.showTutorial)   // Unchanged
        assertEquals(false, patched.showNewConceptInfo) // Unchanged
    }

    @Test
    fun `PATCH config should update multiple fields`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // First GET to create config with defaults
        client.get("/api/v1/user/config") {
            withTestAuth("Z234567")
            contentType(ContentType.Application.Json)
        }

        // Create initial config
        client.put("/api/v1/user/config") {
            withTestAuth("Z234567")
            contentType(ContentType.Application.Json)
            setBody(UserConfig(
                showStartInfo = true,
                showTutorial = true,
                showNewConceptInfo = false
            ))
        }

        // Patch multiple fields
        val patchResponse = client.patch("/api/v1/user/config") {
            withTestAuth("Z234567")
            contentType(ContentType.Application.Json)
            setBody(PatchUserConfig(
                showStartInfo = false,
                showTutorial = false,
                showNewConceptInfo = null
            ))
        }

        assertEquals(HttpStatusCode.OK, patchResponse.status)
        val patched = patchResponse.body<UserConfigDto>()
        assertEquals(false, patched.showStartInfo) // Changed
        assertEquals(false, patched.showTutorial)  // Changed
        assertEquals(false, patched.showNewConceptInfo) // Unchanged
    }

    @Test
    fun `user configs should be isolated per navIdent`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val config1 = UserConfig(
            showStartInfo = true,
            showTutorial = false,
            showNewConceptInfo = true
        )
        val config2 = UserConfig(
            showStartInfo = false,
            showTutorial = true,
            showNewConceptInfo = false
        )

        // First GET for both users to create configs with defaults
        client.get("/api/v1/user/config") {
            withTestAuth("Z111111")
            contentType(ContentType.Application.Json)
        }
        client.get("/api/v1/user/config") {
            withTestAuth("Z222222")
            contentType(ContentType.Application.Json)
        }

        // Create configs for both users
        client.put("/api/v1/user/config") {
            withTestAuth("Z111111")
            contentType(ContentType.Application.Json)
            setBody(config1)
        }
        client.put("/api/v1/user/config") {
            withTestAuth("Z222222")
            contentType(ContentType.Application.Json)
            setBody(config2)
        }

        // Verify user 1 config
        val response1 = client.get("/api/v1/user/config") {
            withTestAuth("Z111111")
            contentType(ContentType.Application.Json)
        }
        val retrieved1 = response1.body<UserConfigDto>()
        assertEquals(true, retrieved1.showStartInfo)
        assertEquals(false, retrieved1.showTutorial)
        assertEquals(true, retrieved1.showNewConceptInfo)

        // Verify user 2 config
        val response2 = client.get("/api/v1/user/config") {
            withTestAuth("Z222222")
            contentType(ContentType.Application.Json)
        }
        val retrieved2 = response2.body<UserConfigDto>()
        assertEquals(false, retrieved2.showStartInfo)
        assertEquals(true, retrieved2.showTutorial)
        assertEquals(false, retrieved2.showNewConceptInfo)
    }
}
