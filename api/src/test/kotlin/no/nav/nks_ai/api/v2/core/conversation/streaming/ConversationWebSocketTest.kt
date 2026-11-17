package no.nav.nks_ai.api.v2.core.conversation.streaming

import com.auth0.jwt.JWT
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.KafkaConfig
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.testutil.TestOAuth2Server
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationWebSocketTest {
    @Test
    fun `remote publication loss closes websocket and invalidates replay`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val source = UUID.randomUUID().toString()
        val conversationId = ConversationId(UUID.randomUUID())
        fixture.bus.recordKafkaValue(
            0, System.currentTimeMillis(), "",
            source, "0", System.currentTimeMillis().toString(),
        )
        fixture.bus.recordEvent(
            ConversationEventRecord(
                1, System.currentTimeMillis(),
                ConversationIdEvent(
                    conversationId, ConversationEvent.ContentUpdated(MessageId(UUID.randomUUID()), "Answer"),
                    source, 0,
                ),
            )
        )
        streamingApplication(fixture.bus)
        application { monitor.subscribe(ApplicationStopped) { fixture.close() } }
        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${conversationId.value}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            assertIs<Frame.Text>(withTimeout(3_000) { incoming.receive() })
            fixture.bus.recordKafkaValue(
                2, System.currentTimeMillis(), "",
                source, "1", System.currentTimeMillis().toString(),
            )
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            assertEquals("Conversation resynchronization required", reason?.message)
            assertTrue(fixture.bus.history(conversationId).isEmpty())
        }
    }

    @Test
    fun `missing remote heartbeat closes websocket without a delivered gap signal`() = testApplication {
        val fixture = StreamingConsumerFixture(leaseMillis = 100)
        fixture.start()
        streamingApplication(fixture.bus)
        application { monitor.subscribe(ApplicationStopped) { fixture.close() } }
        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${UUID.randomUUID()}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            withTimeout(3_000) {
                while (fixture.bus.eventSubscriberCount == 0) delay(10)
            }
            fixture.bus.recordKafkaValue(
                0, System.currentTimeMillis(), "",
                UUID.randomUUID().toString(), "0", System.currentTimeMillis().toString(),
            )
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            assertEquals("Conversation resynchronization required", reason?.message)
        }
    }

    @Test
    fun `websocket replays history and forwards only events for its conversation`() = testApplication {
        val conversationId = ConversationId(UUID.randomUUID())
        val otherConversationId = ConversationId(UUID.randomUUID())
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val bus = fixture.bus
        val historyEvent = ConversationEvent.ContentUpdated(MessageId(UUID.randomUUID()), "history")
        val createdEvent = ConversationEvent.ConversationCreated
        val liveEvent = ConversationEvent.ContentUpdated(MessageId(UUID.randomUUID()), "live")
        bus.recordEvent(
            ConversationEventRecord(
                offset = 10,
                timestamp = System.currentTimeMillis(),
                idEvent = ConversationIdEvent(conversationId, historyEvent),
            )
        )

        application {
            monitor.subscribe(ApplicationStopped) { fixture.close() }
            install(Authentication) {
                bearer("test") {
                    authenticate { credential -> JWTPrincipal(JWT.decode(credential.token)) }
                }
            }
            install(ServerWebSockets)
            routing {
                route("/api/v2") {
                    authenticate("test") {
                        conversationWebSocketV2(
                            canAccessConversation = { requestedId, navIdent ->
                                requestedId == conversationId &&
                                    navIdent.plaintext.value == TestOAuth2Server.NAV_IDENT
                            },
                            conversationEventBus = bus,
                        )
                    }
                }
            }
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        client.webSocket(
            urlString = "/api/v2/conversations/${conversationId.value}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, "Bearer ${TestOAuth2Server.userToken()}") },
        ) {
            val replayed = withTimeout(3_000) { incoming.receive() }
            assertIs<Frame.Text>(replayed)
            assertEquals(Json.encodeToString<ConversationEvent>(historyEvent), replayed.readText())

            withTimeout(3_000) {
                while (bus.eventSubscriberCount == 0) delay(10)
            }
            bus.recordEvent(
                ConversationEventRecord(
                    offset = 10,
                    timestamp = System.currentTimeMillis(),
                    idEvent = ConversationIdEvent(conversationId, historyEvent),
                )
            )
            bus.recordEvent(
                ConversationEventRecord(
                    offset = 11,
                    timestamp = System.currentTimeMillis(),
                    idEvent = ConversationIdEvent(conversationId, createdEvent),
                )
            )
            bus.recordEvent(
                ConversationEventRecord(
                    offset = 12,
                    timestamp = System.currentTimeMillis(),
                    idEvent = ConversationIdEvent(otherConversationId, historyEvent),
                )
            )
            bus.recordEvent(
                ConversationEventRecord(
                    offset = 13,
                    timestamp = System.currentTimeMillis(),
                    idEvent = ConversationIdEvent(conversationId, liveEvent),
                )
            )

            val forwarded = withTimeout(3_000) { incoming.receive() }
            assertIs<Frame.Text>(forwarded)
            assertEquals(Json.encodeToString<ConversationEvent>(liveEvent), forwarded.readText())
            close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
        }
    }

    @Test
    fun `websocket denies a different owner`() = testApplication {
        val conversationId = ConversationId(UUID.randomUUID())
        val bus = ConversationEventBus(
            KafkaConfig(
                brokers = "localhost:9092",
                conversationEventsTopic = "test.conversation-events",
                enabled = false,
            )
        )

        application {
            install(Authentication) {
                bearer("test") {
                    authenticate { credential -> JWTPrincipal(JWT.decode(credential.token)) }
                }
            }
            install(ServerWebSockets)
            routing {
                route("/api/v2") {
                    authenticate("test") {
                        conversationWebSocketV2(
                            canAccessConversation = { _, navIdent ->
                                navIdent.plaintext.value == TestOAuth2Server.NAV_IDENT
                            },
                            conversationEventBus = bus,
                        )
                    }
                }
            }
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        client.webSocket(
            urlString = "/api/v2/conversations/${conversationId.value}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, "Bearer ${TestOAuth2Server.tokenFor("B654321")}") },
        ) {
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            assertEquals("Conversation not found", reason?.message)
        }
    }

    @Test
    fun `websocket is unavailable before assignment`() = testApplication {
        val conversationId = ConversationId(UUID.randomUUID())
        val bus = ConversationEventBus(streamingTestConfig())
        streamingApplication(bus)

        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${conversationId.value}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            assertEquals(0, bus.eventSubscriberCount)
        }
    }

    @Test
    fun `websocket closes when consumer loses its assignment`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        streamingApplication(fixture.bus)
        application { monitor.subscribe(ApplicationStopped) { fixture.close() } }

        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${UUID.randomUUID()}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            withTimeout(3_000) {
                while (fixture.bus.eventSubscriberCount == 0) delay(10)
            }
            fixture.consumer.schedulePollTask { fixture.consumer.rebalance(emptyList()) }
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            assertFalse(fixture.bus.isReady)
            withTimeout(3_000) {
                while (fixture.bus.eventSubscriberCount != 0) delay(10)
            }
            assertEquals(0, fixture.bus.eventSubscriberCount)
        }
    }

    @Test
    fun `websocket closes even if rebalance restores readiness before collection`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        streamingApplication(fixture.bus)
        application { monitor.subscribe(ApplicationStopped) { fixture.close() } }

        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${UUID.randomUUID()}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            withTimeout(3_000) {
                while (fixture.bus.eventSubscriberCount == 0) delay(10)
            }
            fixture.consumer.schedulePollTask {
                fixture.consumer.rebalance(fixture.consumer.assignment().toList())
            }
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            withTimeout(3_000) {
                while (!fixture.bus.isReady) delay(10)
            }
        }
    }

    @Test
    fun `websocket closes when consumer shuts down`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        streamingApplication(fixture.bus)
        application { monitor.subscribe(ApplicationStopped) { fixture.close() } }

        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${UUID.randomUUID()}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            withTimeout(3_000) {
                while (fixture.bus.eventSubscriberCount == 0) delay(10)
            }
            fixture.close()
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
        }
    }

    private fun bearerToken(): String = "Bearer ${TestOAuth2Server.userToken()}"

    private fun TestApplicationBuilder.streamingApplication(
        bus: ConversationEventBus,
    ) {
        application {
            install(Authentication) {
                bearer("test") {
                    authenticate { credential -> JWTPrincipal(JWT.decode(credential.token)) }
                }
            }
            install(ServerWebSockets)
            routing {
                route("/api/v2") {
                    authenticate("test") {
                        conversationWebSocketV2({ _, _ -> true }, bus)
                    }
                }
            }
        }
    }
}
