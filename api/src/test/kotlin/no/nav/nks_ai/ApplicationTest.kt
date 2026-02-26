package no.nav.nks_ai

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import no.nav.nks_ai.api.app.plugins.configureMonitoring
import no.nav.nks_ai.api.app.plugins.healthRoutes

class ApplicationTest {
    @Test
    fun testHealth() = testApplication {
        application {
            configureMonitoring()
        }

        routing {
            route("/internal") {
                healthRoutes()
            }
        }

        client.get("/internal/is_alive").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("alive", bodyAsText())
        }

        client.get("/internal/is_ready").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("ready", bodyAsText())
        }

        client.get("/internal/prometheus").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue { bodyAsText().isNotEmpty() }
        }
    }
}
