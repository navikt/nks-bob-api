package no.nav.nks_ai.integration

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.core.notification.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Notification API.
 * Tests the full stack from HTTP API through service layer to database.
 */
class NotificationIntegrationTest : ApiIntegrationTestBase() {

    @Test
    fun `addNotification should create new notification in database`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val response = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "Test News",
                content = "This is test news content"
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val notification = response.body<Notification>()
        assertEquals("Test News", notification.title)
        assertEquals("This is test news content", notification.content)
        assertEquals(NotificationType.News, notification.notificationType)
    }

    @Test
    fun `getNotifications should retrieve all notifications from database`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // Create multiple notifications
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "News 1",
                content = "Content 1"
            ))
        }
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.Error,
                title = "Error 1",
                content = "Error Content 1"
            ))
        }

        val response = client.get("/api/v1/notifications") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val notifications = response.body<List<Notification>>()
        assertTrue(notifications.size >= 2)
    }

    @Test
    fun `getNotification should retrieve specific notification by id`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.Warning,
                title = "Warning Title",
                content = "Warning Content"
            ))
        }
        val created = createResponse.body<Notification>()

        val response = client.get("/api/v1/notifications/${created.id.value}") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val notification = response.body<Notification>()
        assertEquals(created.id, notification.id)
        assertEquals("Warning Title", notification.title)
        assertEquals("Warning Content", notification.content)
        assertEquals(NotificationType.Warning, notification.notificationType)
    }

    @Test
    fun `getNotification should return error for non-existent id`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val fakeId = java.util.UUID.randomUUID()
        val response = client.get("/api/v1/notifications/$fakeId") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `getNewsNotifications should only return News type notifications`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // Create different types
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "News Item",
                content = "News Content"
            ))
        }
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.Error,
                title = "Error Item",
                content = "Error Content"
            ))
        }

        val response = client.get("/api/v1/notifications/news") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val news = response.body<List<NewsNotification>>()
        assertTrue(news.isNotEmpty())
    }

    @Test
    fun `getErrorNotifications should return Error and Warning type notifications`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // Create different types
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.Error,
                title = "Error Item",
                content = "Error Content"
            ))
        }
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.Warning,
                title = "Warning Item",
                content = "Warning Content"
            ))
        }
        client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "News Item",
                content = "News Content"
            ))
        }

        val response = client.get("/api/v1/notifications/errors") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val errors = response.body<List<ErrorNotification>>()
        assertTrue(errors.all {
            it.notificationType == NotificationType.Error ||
            it.notificationType == NotificationType.Warning
        })
    }

    @Test
    fun `updateNotification should update all fields`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "Original Title",
                content = "Original Content"
            ))
        }
        val created = createResponse.body<Notification>()

        val expiresAt = LocalDateTime.parse("2026-12-31T23:59:59")
        val response = client.put("/api/v1/admin/notifications/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = expiresAt,
                notificationType = NotificationType.Warning,
                title = "Updated Title",
                content = "Updated Content"
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<Notification>()
        assertEquals("Updated Title", updated.title)
        assertEquals("Updated Content", updated.content)
        assertEquals(NotificationType.Warning, updated.notificationType)
        assertEquals(expiresAt, updated.expiresAt)
    }

    @Test
    fun `patchNotification should update only specified fields`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "Original Title",
                content = "Original Content"
            ))
        }
        val created = createResponse.body<Notification>()

        val response = client.patch("/api/v1/admin/notifications/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(PatchNotification(
                title = "Patched Title"
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val patched = response.body<Notification>()
        assertEquals("Patched Title", patched.title)
        assertEquals("Original Content", patched.content) // Should remain unchanged
        assertEquals(NotificationType.News, patched.notificationType) // Should remain unchanged
    }

    @Test
    fun `deleteNotification should remove notification from database`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val createResponse = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "To Be Deleted",
                content = "Delete Me"
            ))
        }
        val created = createResponse.body<Notification>()

        val deleteResponse = client.delete("/api/v1/admin/notifications/${created.id.value}") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val getResponse = client.get("/api/v1/notifications/${created.id.value}") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `notifications should support expiresAt field`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val expiresAt = LocalDateTime.parse("2026-12-31T23:59:59")
        val response = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = expiresAt,
                notificationType = NotificationType.Warning,
                title = "Expiring Warning",
                content = "This warning expires"
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val notification = response.body<Notification>()
        assertEquals(expiresAt, notification.expiresAt)
    }

    @Test
    fun `notifications should be sorted by createdAt descending`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // Create notifications with some delay to ensure different timestamps
        val firstResponse = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "First",
                content = "First Content"
            ))
        }
        val first = firstResponse.body<Notification>()

        Thread.sleep(10) // Small delay to ensure different timestamps

        val secondResponse = client.post("/api/v1/admin/notifications") {
            withTestAdminAuth()
            contentType(ContentType.Application.Json)
            setBody(CreateNotification(
                expiresAt = null,
                notificationType = NotificationType.News,
                title = "Second",
                content = "Second Content"
            ))
        }
        val second = secondResponse.body<Notification>()

        val response = client.get("/api/v1/notifications/news") {
            withTestAuth()
            contentType(ContentType.Application.Json)
        }
        val news = response.body<List<NewsNotification>>()

        // Should be sorted with most recent first
        val firstIndex = news.indexOfFirst { it.id == first.id }
        val secondIndex = news.indexOfFirst { it.id == second.id }
        assertTrue(secondIndex < firstIndex, "Most recent notification should come first")
    }
}
