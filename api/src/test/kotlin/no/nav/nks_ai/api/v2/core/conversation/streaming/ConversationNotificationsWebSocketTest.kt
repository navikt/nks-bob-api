package no.nav.nks_ai.api.v2.core.conversation.streaming

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.core.user.NavIdent
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConversationNotificationsWebSocketTest {
    @Test
    fun `websocket sends only its owners created conversation as the notification json shape`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val owner = NavIdent("A123456")
        val foreignOwner = NavIdent("B654321")
        val foreignConversationId = ConversationId(UUID.randomUUID())
        val ownedConversationId = ConversationId(UUID.randomUUID())
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = { id ->
                if (id == foreignConversationId) foreignOwner.hash.right() else owner.hash.right()
            },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)

        try {
            createClient { install(ClientWebSockets) }.webSocket(
                urlString = NOTIFICATIONS_PATH,
                request = {
                    headers.append(HttpHeaders.Authorization, bearerToken("A123456"))
                    headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                withTimeout(3_000) {
                    while (hub.subscriptionCount == 0) delay(10)
                }
                fixture.publish(foreignConversationId)
                fixture.publish(ownedConversationId)

                val frame = withTimeout(3_000) { incoming.receive() }
                assertIs<Frame.Text>(frame)
                val json = Json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals(setOf("type", "conversationId"), json.keys)
                assertEquals("ConversationCreated", json.getValue("type").jsonPrimitive.content)
                assertEquals(ownedConversationId.value.toString(), json.getValue("conversationId").jsonPrimitive.content)
                close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
            }
        } finally {
            hub.close()
            fixture.close()
        }
    }

    @Test
    fun `websocket is live only and does not replay a created event to a new connection`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val owner = NavIdent("A123456")
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = { owner.hash.right() },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)
        val previousId = ConversationId(UUID.randomUUID())
        fixture.publish(previousId)

        try {
            createClient { install(ClientWebSockets) }.webSocket(
                urlString = NOTIFICATIONS_PATH,
                request = {
                    headers.append(HttpHeaders.Authorization, bearerToken("A123456"))
                    headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                withTimeout(3_000) {
                    while (hub.subscriptionCount == 0) delay(10)
                }
                val newId = ConversationId(UUID.randomUUID())
                fixture.publish(newId)

                val frame = withTimeout(3_000) { incoming.receive() }
                assertIs<Frame.Text>(frame)
                val json = Json.parseToJsonElement(frame.readText()).jsonObject
                assertEquals(newId.value.toString(), json.getValue("conversationId").jsonPrimitive.content)
                assertEquals("ConversationCreated", json.getValue("type").jsonPrimitive.content)
                close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
            }
        } finally {
            hub.close()
            fixture.close()
        }
    }

    @Test
    fun `websocket requires authentication middleware`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val owner = NavIdent("A123456")
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = { owner.hash.right() },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)

        try {
            val failure = assertFailsWith<IllegalStateException> {
                createClient { install(ClientWebSockets) }.webSocket(
                    urlString = NOTIFICATIONS_PATH,
                    request = { headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN) },
                ) {
                    error("Unauthenticated connection was accepted")
                }
            }
            assertEquals("WebSocket connection failed", failure.message)
            assertEquals(0, hub.subscriptionCount)
        } finally {
            hub.close()
            fixture.close()
        }
    }

    @Test
    fun `websocket rejects missing identity expiry and non-allowlisted origin`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val owner = NavIdent("A123456")
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = { owner.hash.right() },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)

        try {
            val client = createClient { install(ClientWebSockets) }
            assertRejected(client, token(navIdent = null, expiration = Instant.now().plusSeconds(60)), ALLOWED_ORIGIN)
            assertRejected(client, token(navIdent = "A123456", expiration = null), ALLOWED_ORIGIN)
            assertRejected(client, token(expiration = Instant.now().plusSeconds(60)), null)
            assertRejected(client, token(expiration = Instant.now().plusSeconds(60)), "https://untrusted.example")
            assertEquals(0, hub.subscriptionCount)
        } finally {
            hub.close()
            fixture.close()
        }
    }

    @Test
    fun `websocket closes when its authentication token expires`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val owner = NavIdent("A123456")
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = { owner.hash.right() },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)

        try {
            createClient { install(ClientWebSockets) }.webSocket(
                urlString = NOTIFICATIONS_PATH,
                request = {
                    headers.append(HttpHeaders.Authorization, bearerToken("A123456", Instant.now().plusSeconds(2)))
                    headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                withTimeout(5_000) {
                    while (hub.subscriptionCount == 0) delay(10)
                }
                val reason = withTimeout(5_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            }
        } finally {
            hub.close()
            fixture.close()
        }
    }

    @Test
    fun `ownership lookup failure closes the websocket with a temporary error`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = {
                ApplicationError.InternalServerError("Database error", "synthetic internal detail").left()
            },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)
        try {
            createClient { install(ClientWebSockets) }.webSocket(
                urlString = NOTIFICATIONS_PATH,
                request = {
                    headers.append(HttpHeaders.Authorization, bearerToken("A123456"))
                    headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                withTimeout(3_000) {
                    while (hub.subscriptionCount == 0) delay(10)
                }
                fixture.publish(ConversationId(UUID.randomUUID()))
                val reason = withTimeout(3_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
                assertEquals("Notification stream unavailable", reason?.message)
            }
        } finally {
            hub.close()
            fixture.close()
        }
    }

    @Test
    fun `websocket rejects connections before assignment and closes after assignment is lost`() = testApplication {
        val fixture = StreamingConsumerFixture()
        val owner = NavIdent("A123456")
        val hub = ConversationNotificationHub(
            eventBus = fixture.bus,
            loadOwnerHash = { owner.hash.right() },
        )
        hub.start(fixture.scope)
        notificationApplication(fixture, hub)

        try {
            createClient { install(ClientWebSockets) }.webSocket(
                urlString = NOTIFICATIONS_PATH,
                request = {
                    headers.append(HttpHeaders.Authorization, bearerToken("A123456"))
                    headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                val reason = withTimeout(3_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
                assertEquals(0, hub.subscriptionCount)
            }

            fixture.start()
            createClient { install(ClientWebSockets) }.webSocket(
                urlString = NOTIFICATIONS_PATH,
                request = {
                    headers.append(HttpHeaders.Authorization, bearerToken("A123456"))
                    headers.append(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                withTimeout(3_000) {
                    while (hub.subscriptionCount == 0) delay(10)
                }
                fixture.consumer.schedulePollTask { fixture.consumer.rebalance(emptyList()) }
                val reason = withTimeout(3_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            }
        } finally {
            hub.close()
            fixture.close()
        }
    }

    private fun TestApplicationBuilder.notificationApplication(
        fixture: StreamingConsumerFixture,
        hub: ConversationNotificationHub,
    ) {
        application {
            monitor.subscribe(ApplicationStopped) {
                hub.close()
                fixture.close()
            }
            install(Authentication) {
                bearer("test") {
                    authenticate { credential -> JWTPrincipal(JWT.decode(credential.token)) }
                }
            }
            install(ServerWebSockets)
            routing {
                route("/api/v2") {
                    authenticate("test") {
                        conversationNotificationsWebSocketV2(
                            eventBus = fixture.bus,
                            hub = hub,
                            allowedOrigins = setOf(ALLOWED_ORIGIN),
                        )
                    }
                }
            }
        }
    }

    private suspend fun assertRejected(
        client: io.ktor.client.HttpClient,
        token: String,
        origin: String?,
    ) {
        client.webSocket(
            urlString = NOTIFICATIONS_PATH,
            request = {
                headers.append(HttpHeaders.Authorization, "Bearer $token")
                origin?.let { headers.append(HttpHeaders.Origin, it) }
            },
        ) {
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    private fun bearerToken(navIdent: String, expiration: Instant = Instant.now().plusSeconds(60)) =
        "Bearer ${token(navIdent, expiration)}"

    private fun token(
        navIdent: String? = "A123456",
        expiration: Instant? = Instant.now().plusSeconds(60),
    ): String {
        val builder = JWT.create()
        navIdent?.let { builder.withClaim("NAVident", it) }
        expiration?.let { builder.withExpiresAt(Date.from(it)) }
        return builder.sign(Algorithm.none())
    }

    private companion object {
        const val NOTIFICATIONS_PATH = "/api/v2/conversations/notifications/ws"
        const val ALLOWED_ORIGIN = "https://frontend.example"
    }
}

private suspend fun StreamingConsumerFixture.publish(conversationId: ConversationId) {
    bus.recordEvent(
        ConversationEventRecord(
            offset = 1,
            timestamp = System.currentTimeMillis(),
            idEvent = ConversationIdEvent(conversationId, ConversationEvent.ConversationCreated),
        ),
    )
}
