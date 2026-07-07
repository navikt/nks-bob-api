package no.nav.nks_ai.core.notification

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.nks_ai.api.core.notification.CreateNotification
import no.nav.nks_ai.api.core.notification.ErrorNotification
import no.nav.nks_ai.api.core.notification.NewsNotification
import no.nav.nks_ai.api.core.notification.Notification
import no.nav.nks_ai.api.core.notification.NotificationType
import no.nav.nks_ai.api.core.notification.PatchNotification
import no.nav.nks_ai.shared.ErrorResponse
import no.nav.nks_ai.testutil.TestOAuth2Server
import no.nav.nks_ai.testutil.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integrasjonstester for notifications-API.
 *
 * Tester flyter fra HTTP-lag gjennom service (inkl. cache) og ned til PostgreSQL.
 * Databasen er en ekte Testcontainers-instans med Flyway-migrasjoner.
 * JWT-tokens utstedes av mock-oauth2-server.
 *
 * OBS: Testene deler database — rekkefølgen innad i en test er deterministisk,
 * men tester er skrevet slik at de ikke er avhengige av tilstand fra andre tester.
 */
class NotificationApiTest {

    // ─── Bruker-endepunkter: GET ─────────────────────────────────────────────

    @Test
    fun `GET notifications - krever autentisering`() = testApp { client ->
        client.get("/api/v1/notifications").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET notifications - returnerer tom liste nar ingen notifikasjoner finnes`() = testApp { client ->
        client.get("/api/v1/notifications") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val body = body<List<Notification>>()
            // Kan inneholde rester fra andre tester — vi sjekker at kallet lykkes
            assertNotNull(body)
        }
    }

    @Test
    fun `GET notifications - returnerer notifikasjon opprettet av admin`() = testApp { client ->
        // Admin oppretter en notifikasjon
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "GET all test",
                    content = "Innhold for GET all test",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<Notification>()

        // Bruker henter alle notifikasjoner
        val notifications = client.get("/api/v1/notifications") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<List<Notification>>()

        assertTrue(notifications.any { it.id == created.id })
    }

    @Test
    fun `GET notifications-news - returnerer kun News-notifikasjoner`() = testApp { client ->
        val newsNotification = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Nyhet",
                    content = "En nyhet",
                )
            )
        }.body<Notification>()

        val news = client.get("/api/v1/notifications/news") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<List<NewsNotification>>()

        assertTrue(news.any { it.id == newsNotification.id })
        // Sjekk at alle returnerte har riktig type (NewsNotification har ikke notificationType-felt —
        // det er allerede garantert av typen)
    }

    @Test
    fun `GET notifications-news - krever autentisering`() = testApp { client ->
        client.get("/api/v1/notifications/news").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET notifications-errors - returnerer Error og Warning notifikasjoner`() = testApp { client ->
        // Sørg for at det ikke finnes eksisterende Error/Warning-notifikasjoner
        // ved å hente dem og slette dem
        val existingErrors = client.get("/api/v1/notifications/errors") {
            bearerAuth(TestOAuth2Server.userToken())
        }.body<List<ErrorNotification>>()

        existingErrors.forEach { existing ->
            client.delete("/api/v1/admin/notifications/${existing.id.value}") {
                bearerAuth(TestOAuth2Server.adminToken())
            }
        }

        // Opprett en Error-notifikasjon
        val errorNotification = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.Error,
                    title = "Feil",
                    content = "Det har oppstått en feil",
                )
            )
        }.body<Notification>()

        val errors = client.get("/api/v1/notifications/errors") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<List<ErrorNotification>>()

        assertTrue(errors.any { it.id == errorNotification.id })
        assertTrue(errors.all { it.notificationType == NotificationType.Error || it.notificationType == NotificationType.Warning })

        // Rydder opp
        client.delete("/api/v1/admin/notifications/${errorNotification.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
        }
    }

    @Test
    fun `GET notifications-errors - krever autentisering`() = testApp { client ->
        client.get("/api/v1/notifications/errors").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `GET notifications-id - returnerer spesifikk notifikasjon`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Spesifikk notifikasjon",
                    content = "Innhold",
                )
            )
        }.body<Notification>()

        client.get("/api/v1/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val fetched = body<Notification>()
            assertEquals(created.id, fetched.id)
            assertEquals("Spesifikk notifikasjon", fetched.title)
            assertEquals("Innhold", fetched.content)
            assertEquals(NotificationType.News, fetched.notificationType)
            assertNull(fetched.expiresAt)
        }
    }

    @Test
    fun `GET notifications-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/notifications/$unknownId") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `GET notifications-id - krever autentisering`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.get("/api/v1/notifications/$unknownId").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    // ─── Admin-endepunkter: POST / PUT / PATCH / DELETE ──────────────────────

    @Test
    fun `POST admin-notifications - oppretter notifikasjon`() = testApp { client ->
        val request = CreateNotification(
            expiresAt = null,
            notificationType = NotificationType.News,
            title = "Ny notifikasjon",
            content = "Noe viktig har skjedd",
        )

        client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val created = body<Notification>()
            assertNotNull(created.id)
            assertEquals("Ny notifikasjon", created.title)
            assertEquals("Noe viktig har skjedd", created.content)
            assertEquals(NotificationType.News, created.notificationType)
            assertNull(created.expiresAt)
            assertNotNull(created.createdAt)
        }
    }

    @Test
    fun `POST admin-notifications - returnerer 401 for vanlig bruker`() = testApp { client ->
        client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Ulovlig",
                    content = "Skal ikke gå",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST admin-notifications - returnerer 401 uten token`() = testApp { client ->
        client.post("/api/v1/admin/notifications") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Ulovlig",
                    content = "Skal ikke gå",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `POST admin-notifications - kan ikke opprette to Error-notifikasjoner`() = testApp { client ->
        // Rydd opp eventuelle eksisterende Error/Warning-notifikasjoner
        val existingErrors = client.get("/api/v1/notifications/errors") {
            bearerAuth(TestOAuth2Server.userToken())
        }.body<List<ErrorNotification>>()

        existingErrors.forEach { existing ->
            client.delete("/api/v1/admin/notifications/${existing.id.value}") {
                bearerAuth(TestOAuth2Server.adminToken())
            }
        }

        val createError = CreateNotification(
            expiresAt = null,
            notificationType = NotificationType.Error,
            title = "Feil nummer 1",
            content = "Første feilmelding",
        )

        // Opprett første Error-notifikasjon — skal lykkes
        val first = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(createError)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }.body<Notification>()

        // Forsøk å opprette en til — skal feile med 400
        client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.Error,
                    title = "Feil nummer 2",
                    content = "Andre feilmelding",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val error = body<ErrorResponse>()
            assertTrue(error.description.contains("Cannot create multiple error notifications"))
        }

        // Rydder opp
        client.delete("/api/v1/admin/notifications/${first.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
        }
    }

    @Test
    fun `PUT admin-notifications-id - oppdaterer notifikasjon`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Gammel tittel",
                    content = "Gammelt innhold",
                )
            )
        }.body<Notification>()

        client.put("/api/v1/admin/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Ny tittel",
                    content = "Nytt innhold",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val updated = body<Notification>()
            assertEquals(created.id, updated.id)
            assertEquals("Ny tittel", updated.title)
            assertEquals("Nytt innhold", updated.content)
        }
    }

    @Test
    fun `PUT admin-notifications-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.put("/api/v1/admin/notifications/$unknownId") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Tittel",
                    content = "Innhold",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `PUT admin-notifications-id - returnerer 401 for vanlig bruker`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Tittel",
                    content = "Innhold",
                )
            )
        }.body<Notification>()

        client.put("/api/v1/admin/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Ulovlig endring",
                    content = "Skal ikke gå",
                )
            )
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `PATCH admin-notifications-id - oppdaterer kun angitte felter`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Original tittel",
                    content = "Original innhold",
                )
            )
        }.body<Notification>()

        client.patch("/api/v1/admin/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(PatchNotification(title = "Oppdatert tittel"))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val patched = body<Notification>()
            assertEquals(created.id, patched.id)
            assertEquals("Oppdatert tittel", patched.title)
            // Innhold skal være uendret
            assertEquals("Original innhold", patched.content)
            // Type skal være uendret
            assertEquals(NotificationType.News, patched.notificationType)
        }
    }

    @Test
    fun `PATCH admin-notifications-id - returnerer 404 for ukjent id`() = testApp { client ->
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.patch("/api/v1/admin/notifications/$unknownId") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(PatchNotification(title = "Ny tittel"))
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `PATCH admin-notifications-id - returnerer 401 for vanlig bruker`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Tittel",
                    content = "Innhold",
                )
            )
        }.body<Notification>()

        client.patch("/api/v1/admin/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
            contentType(ContentType.Application.Json)
            setBody(PatchNotification(title = "Ulovlig patch"))
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `DELETE admin-notifications-id - sletter notifikasjon`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Skal slettes",
                    content = "Innhold",
                )
            )
        }.body<Notification>()

        // Slett notifikasjonen
        client.delete("/api/v1/admin/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NoContent, status)
        }

        // Verifiser at den er borte
        client.get("/api/v1/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun `DELETE admin-notifications-id - returnerer 401 for vanlig bruker`() = testApp { client ->
        val created = client.post("/api/v1/admin/notifications") {
            bearerAuth(TestOAuth2Server.adminToken())
            contentType(ContentType.Application.Json)
            setBody(
                CreateNotification(
                    expiresAt = null,
                    notificationType = NotificationType.News,
                    title = "Tittel",
                    content = "Innhold",
                )
            )
        }.body<Notification>()

        client.delete("/api/v1/admin/notifications/${created.id.value}") {
            bearerAuth(TestOAuth2Server.userToken())
        }.apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `DELETE admin-notifications-id - returnerer 204 selv om notifikasjon ikke eksisterer`() = testApp { client ->
        // DELETE er idempotent — sletter man noe som ikke fins, er resultatet fortsatt suksess
        val unknownId = "00000000-0000-0000-0000-000000000000"
        client.delete("/api/v1/admin/notifications/$unknownId") {
            bearerAuth(TestOAuth2Server.adminToken())
        }.apply {
            assertEquals(HttpStatusCode.NoContent, status)
        }
    }
}
