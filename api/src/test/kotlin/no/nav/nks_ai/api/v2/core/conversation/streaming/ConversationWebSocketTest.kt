package no.nav.nks_ai.api.v2.core.conversation.streaming

import arrow.core.right
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
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.KafkaConfig
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.active.ActiveConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationPageRequest
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationService
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.testutil.TestOAuth2Server
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import java.util.concurrent.atomic.AtomicInteger

class ConversationWebSocketTest {
    @Test
    fun `websocket replays history and forwards only events for its conversation`() = testApplication {
        val conversationId = ConversationId(UUID.randomUUID())
        val otherConversationId = ConversationId(UUID.randomUUID())
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val bus = fixture.bus
        val historyEvent = ConversationEvent.ContentUpdated(MessageId(UUID.randomUUID()), "history")
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
                            activeConversationService = ActiveConversationStub(),
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
                    idEvent = ConversationIdEvent(otherConversationId, historyEvent),
                )
            )
            bus.recordEvent(
                ConversationEventRecord(
                    offset = 12,
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
                            activeConversationService = ActiveConversationStub(),
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
    fun `websocket is unavailable before assignment without registering an active connection`() = testApplication {
        val conversationId = ConversationId(UUID.randomUUID())
        val bus = ConversationEventBus(streamingTestConfig())
        val active = ActiveConversationStub()
        streamingApplication(bus, active)

        createClient { install(ClientWebSockets) }.webSocket(
            urlString = "/api/v2/conversations/${conversationId.value}/messages/ws",
            request = { headers.append(HttpHeaders.Authorization, bearerToken()) },
        ) {
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            assertEquals(0, active.connections.get())
            assertEquals(0, bus.eventSubscriberCount)
        }
    }

    @Test
    fun `websocket closes when consumer loses its assignment`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        val active = ActiveConversationStub()
        streamingApplication(fixture.bus, active)
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
                while (active.disconnections.get() == 0) delay(10)
            }
            assertEquals(1, active.connections.get())
            assertEquals(1, active.disconnections.get())
        }
    }

    @Test
    fun `websocket closes even if rebalance restores readiness before collection`() = testApplication {
        val fixture = StreamingConsumerFixture()
        fixture.start()
        streamingApplication(fixture.bus, ActiveConversationStub())
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
        streamingApplication(fixture.bus, ActiveConversationStub())
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
        active: ActiveConversationService,
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
                        conversationWebSocketV2({ _, _ -> true }, bus, active)
                    }
                }
            }
        }
    }

    private class ActiveConversationStub : ActiveConversationService {
        val connections = AtomicInteger()
        val disconnections = AtomicInteger()

        override suspend fun connect(conversationId: ConversationId, owner: NavIdent): ApplicationResult<UUID> {
            connections.incrementAndGet()
            return UUID.randomUUID().right()
        }

        override suspend fun renew(connectionId: UUID): ApplicationResult<Unit> = Unit.right()

        override suspend fun disconnect(connectionId: UUID): ApplicationResult<Unit> {
            disconnections.incrementAndGet()
            return Unit.right()
        }

        override suspend fun getActiveConversations(
            owner: NavIdent,
            request: ActiveConversationPageRequest,
        ): ApplicationResult<Page<ActiveConversation>> = error("Not used by this test")

        override suspend fun getStatistics() = error("Not used by this test")

        override suspend fun deleteExpiredConnections() = error("Not used by this test")
    }
}
