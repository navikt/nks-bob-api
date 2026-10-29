package no.nav.nks_ai.api.v2.core.conversation.streaming

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.user.NavIdent
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationNotificationHubTest {
    @Test
    fun `only the conversation owner receives its id`() = runBlocking {
        val owner = NavIdent("A123456")
        val otherUser = NavIdent("B654321")
        val ownedConversation = ConversationId(UUID.randomUUID())
        val fixture = HubFixture(loadOwnerHash = { conversationId ->
            if (conversationId == ownedConversation) owner.hash.right() else otherUser.hash.right()
        })
        try {
            val ownerSubscription = fixture.hub.subscribe(owner) ?: error("Owner subscription unavailable")
            val otherSubscription = fixture.hub.subscribe(otherUser) ?: error("Other subscription unavailable")
            val foreignConversation = ConversationId(UUID.randomUUID())

            fixture.publish(foreignConversation)
            fixture.publish(ownedConversation)

            assertEquals(ownedConversation, withTimeout(3_000) { ownerSubscription.events.receive() })
            assertEquals(foreignConversation, withTimeout(3_000) { otherSubscription.events.receive() })
            assertFalse(otherSubscription.events.tryReceive().isSuccess)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `multiple tabs for one owner receive an event after one ownership lookup`() = runBlocking {
        val owner = NavIdent("A123456")
        val loadCount = AtomicInteger()
        val fixture = HubFixture(loadOwnerHash = { loadCount.incrementAndGet(); owner.hash.right() })
        try {
            val firstTab = fixture.hub.subscribe(owner) ?: error("First subscription unavailable")
            val secondTab = fixture.hub.subscribe(owner) ?: error("Second subscription unavailable")
            val conversationId = ConversationId(UUID.randomUUID())

            fixture.publish(conversationId)

            assertEquals(conversationId, withTimeout(3_000) { firstTab.events.receive() })
            assertEquals(conversationId, withTimeout(3_000) { secondTab.events.receive() })
            assertEquals(1, loadCount.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `global and per-user limits reject subscriptions and disconnect releases capacity`() = runBlocking {
        val firstUser = NavIdent("A123456")
        val secondUser = NavIdent("B654321")
        val fixture = HubFixture(
            loadOwnerHash = { firstUser.hash.right() },
            maxConnections = 2,
            maxConnectionsPerUser = 1,
        )
        try {
            val first = fixture.hub.subscribe(firstUser) ?: error("First subscription unavailable")
            assertNull(fixture.hub.subscribe(firstUser), "Per-user connection limit must be enforced")
            val second = fixture.hub.subscribe(secondUser) ?: error("Second user should fit global limit")
            assertNull(fixture.hub.subscribe(NavIdent("C987654")), "Global connection limit must be enforced")

            first.close()
            assertEquals(1, fixture.hub.subscriptionCount)
            val replacement = fixture.hub.subscribe(firstUser)
            assertTrue(replacement != null, "Disconnect must release per-user and global capacity")
            assertNull(fixture.hub.subscribe(NavIdent("D123456")), "Global connection limit must remain enforced")

            second.close()
            replacement.close()
            assertEquals(0, fixture.hub.subscriptionCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `overflow cancels a bounded subscriber channel and discards buffered events`(): Unit = runBlocking {
        val owner = NavIdent("A123456")
        val fixture = HubFixture(
            loadOwnerHash = { owner.hash.right() },
            bufferCapacity = 1,
        )
        try {
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")
            val closed = CompletableDeferred<Unit>()
            subscription.events.invokeOnClose { closed.complete(Unit) }
            val firstId = ConversationId(UUID.randomUUID())
            val secondId = ConversationId(UUID.randomUUID())

            fixture.publish(firstId)
            fixture.publish(secondId)

            withTimeout(3_000) { closed.await() }
            assertIs<NotificationStreamUnavailable>(
                withTimeout(3_000) { subscription.events.receiveCatching().exceptionOrNull() },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `conversation not found does not close or notify subscribers`() = runBlocking {
        val owner = NavIdent("A123456")
        val fixture = HubFixture(loadOwnerHash = { id -> ApplicationError.ConversationNotFound(id).left() })
        try {
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")

            fixture.publish(ConversationId(UUID.randomUUID()))

            val result = subscription.events.tryReceive()
            assertFalse(result.isSuccess)
            assertFalse(result.isClosed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ownership lookup failure closes channels as unavailable`(): Unit = runBlocking {
        val owner = NavIdent("A123456")
        val successfulConversation = ConversationId(UUID.randomUUID())
        val failedConversation = ConversationId(UUID.randomUUID())
        val fixture = HubFixture(loadOwnerHash = { conversationId ->
            if (conversationId == successfulConversation) {
                owner.hash.right()
            } else {
                ApplicationError.InternalServerError("Database error", "Unavailable").left()
            }
        })
        try {
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")
            val closed = CompletableDeferred<Unit>()
            subscription.events.invokeOnClose { closed.complete(Unit) }
            fixture.publish(successfulConversation)
            fixture.publish(failedConversation)

            withTimeout(3_000) { closed.await() }
            assertIs<NotificationStreamUnavailable>(
                withTimeout(3_000) { subscription.events.receiveCatching().exceptionOrNull() },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `hub close cancels channels and discards buffered events`() = runBlocking {
        val owner = NavIdent("A123456")
        val firstConversation = ConversationId(UUID.randomUUID())
        val blockingConversation = ConversationId(UUID.randomUUID())
        val blockingLookupStarted = CompletableDeferred<Unit>()
        val fixture = HubFixture(loadOwnerHash = { conversationId ->
            val hash = if (conversationId == blockingConversation) {
                blockingLookupStarted.complete(Unit)
                awaitCancellation()
            } else {
                owner.hash
            }
            hash.right()
        })
        try {
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")
            fixture.publish(firstConversation)
            fixture.publish(blockingConversation)
            withTimeout(3_000) { blockingLookupStarted.await() }
            fixture.hub.close()

            assertIs<NotificationStreamUnavailable>(
                withTimeout(3_000) { subscription.events.receiveCatching().exceptionOrNull() },
            )
            assertEquals(1, fixture.hub.subscriptionCount, "Only Subscription.close releases the connection slot")
            subscription.close()
            assertEquals(0, fixture.hub.subscriptionCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `dispatcher buffer overflow closes subscribers instead of silently skipping notifications`(): Unit = runBlocking {
        val owner = NavIdent("A123456")
        val firstConversation = ConversationId(UUID.randomUUID())
        val lookupStarted = CompletableDeferred<Unit>()
        val finishLookup = CompletableDeferred<Unit>()
        val fixture = HubFixture(loadOwnerHash = { id ->
            if (id == firstConversation) {
                lookupStarted.complete(Unit)
                finishLookup.await()
            }
            owner.hash.right()
        })
        try {
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")
            val closed = CompletableDeferred<Unit>()
            subscription.events.invokeOnClose { closed.complete(Unit) }
            fixture.publish(firstConversation)
            withTimeout(3_000) { lookupStarted.await() }
            repeat(130) { fixture.publish(ConversationId(UUID.randomUUID())) }
            finishLookup.complete(Unit)
            withTimeout(3_000) { closed.await() }
            assertIs<NotificationStreamUnavailable>(subscription.events.receiveCatching().exceptionOrNull())
        } finally {
            finishLookup.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `dispatcher failure does not cancel its parent scope`() = runBlocking {
        val owner = NavIdent("A123456")
        val fixture = HubFixture(
            loadOwnerHash = { throw IllegalStateException("synthetic dispatcher failure") },
            parentJob = Job(),
        )
        try {
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")

            fixture.publish(ConversationId(UUID.randomUUID()))

            assertIs<NotificationStreamUnavailable>(
                withTimeout(3_000) { subscription.events.receiveCatching().exceptionOrNull() },
            )
            assertTrue(fixture.scope.isActive, "An unexpected dispatcher failure must not cancel its parent")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `only conversation created events are dispatched and no earlier events are replayed`() = runBlocking {
        val owner = NavIdent("A123456")
        val loadCount = AtomicInteger()
        val fixture = HubFixture(loadOwnerHash = { loadCount.incrementAndGet(); owner.hash.right() })
        try {
            val oldConversation = ConversationId(UUID.randomUUID())
            fixture.publish(oldConversation)
            val subscription = fixture.hub.subscribe(owner) ?: error("Subscription unavailable")
            assertFalse(subscription.events.tryReceive().isSuccess, "New subscribers must not receive prior events")

            fixture.bus.recordEvent(
                ConversationEventRecord(
                    offset = 2,
                    timestamp = System.currentTimeMillis(),
                    idEvent = ConversationIdEvent(
                        ConversationId(UUID.randomUUID()),
                        ConversationEvent.ContentUpdated(
                            no.nav.nks_ai.api.core.message.MessageId(UUID.randomUUID()),
                            "synthetic content",
                        ),
                    ),
                ),
            )
            val createdConversation = ConversationId(UUID.randomUUID())
            fixture.publish(createdConversation)
            assertEquals(createdConversation, withTimeout(3_000) { subscription.events.receive() })
            assertEquals(1, loadCount.get(), "ContentUpdated must not trigger ownership lookup")
        } finally {
            fixture.close()
        }
    }

    private class HubFixture(
        private val loadOwnerHash: suspend (ConversationId) -> ApplicationResult<String>,
        parentJob: Job = SupervisorJob(),
        maxConnections: Int = 512,
        maxConnectionsPerUser: Int = 5,
        bufferCapacity: Int = 32,
    ) : AutoCloseable {
        val bus = ConversationEventBus(streamingTestConfig())
        val scope = CoroutineScope(parentJob + Dispatchers.Default)
        val hub = ConversationNotificationHub(
            eventBus = bus,
            loadOwnerHash = loadOwnerHash,
            maxConnections = maxConnections,
            maxConnectionsPerUser = maxConnectionsPerUser,
            bufferCapacity = bufferCapacity,
        )

        init {
            hub.start(scope)
        }

        suspend fun publish(conversationId: ConversationId) {
            bus.recordEvent(
                ConversationEventRecord(
                    offset = 1,
                    timestamp = System.currentTimeMillis(),
                    idEvent = ConversationIdEvent(conversationId, ConversationEvent.ConversationCreated),
                ),
            )
        }

        override fun close() {
            hub.close()
            bus.close()
            scope.cancel()
        }
    }
}
