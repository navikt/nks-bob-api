package no.nav.nks_ai.api.v2.core.conversation.streaming

import no.nav.nks_ai.api.app.KafkaConfig
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.clients.producer.ProducerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ConversationEventBusTest {
    @Test
    fun `consumer establishes positions on IO loop before accepting connections`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val positionThread = AtomicReference<Thread>()
        val callerThread = Thread.currentThread()
        val consumer = object : MockConsumer<String, String>("latest") {
            override fun position(partition: TopicPartition, timeout: Duration): Long {
                positionThread.set(Thread.currentThread())
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return 0L
            }
        }
        val fixture = StreamingConsumerFixture(consumer)
        try {
            consumer.schedulePollTask {
                val partition = TopicPartition(streamingTestConfig().conversationEventsTopic, 0)
                consumer.updateEndOffsets(mapOf(partition to 0L))
                consumer.rebalance(listOf(partition))
            }
            fixture.bus.start(fixture.scope)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(fixture.bus.isReady)
            assertNotEquals(callerThread, positionThread.get())
            release.countDown()
            withTimeout(3_000) {
                while (!fixture.bus.isReady) delay(10)
            }
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    @Test
    fun `disabled Kafka remains unavailable even after start`() {
        ConversationEventBus(streamingTestConfig()).use { bus ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                bus.start(scope)
                assertFalse(bus.isReady)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `assignment is unavailable until every partition has an initial position`() {
        val readiness = ConsumerReadiness()
        val first = TopicPartition("events", 0)
        val second = TopicPartition("events", 1)
        val positions = mutableSetOf(first)
        val timeouts = mutableListOf<Duration>()
        val consumer = object : MockConsumer<String, String>("latest") {
            override fun position(partition: TopicPartition, timeout: Duration): Long {
                timeouts += timeout
                if (partition !in positions) throw TimeoutException("Position not initialized")
                return 0
            }
        }
        consumer.use {
            consumer.subscribe(listOf("events"), readiness)
            readiness.establishPositions(consumer)
            assertFalse(readiness.isReady, "No assignment must remain unavailable")
            consumer.rebalance(listOf(first, second))
            assertFalse(readiness.isReady)
            readiness.establishPositions(consumer)
            assertFalse(readiness.isReady, "One initialized partition is not enough")

            positions += second
            readiness.establishPositions(consumer)
            assertTrue(readiness.isReady, "No records are required for readiness")
            assertTrue(timeouts.all { !it.isNegative && it <= Duration.ofSeconds(2) })
        }
    }

    @Test
    fun `rebalance revocation loss and stop reset readiness`() {
        val readiness = ConsumerReadiness()
        val partition = TopicPartition("events", 0)
        MockConsumer<String, String>("latest").use { consumer ->
            consumer.subscribe(listOf("events"), readiness)
            consumer.updateEndOffsets(mapOf(partition to 42L))
            consumer.rebalance(listOf(partition))
            readiness.establishPositions(consumer)
            assertTrue(readiness.isReady)
            assertEquals(42L, consumer.position(partition))

            readiness.onPartitionsRevoked(listOf(partition))
            assertFalse(readiness.isReady)
            readiness.establishPositions(consumer)
            readiness.onPartitionsLost(listOf(partition))
            assertFalse(readiness.isReady)
            readiness.establishPositions(consumer)
            readiness.onPartitionsAssigned(listOf(partition))
            assertFalse(readiness.isReady)
            readiness.establishPositions(consumer)
            readiness.stop()
            assertFalse(readiness.isReady)
            readiness.establishPositions(consumer)
            assertFalse(readiness.isReady, "Shutdown must not be undone by an in-flight initialization")
        }
    }

    @Test
    fun `consumer cancellation resets readiness and closes consumer`() = runBlocking {
        val fixture = StreamingConsumerFixture()
        try {
            fixture.start()
            fixture.scope.cancel()
            withTimeout(3_000) {
                while (fixture.bus.isReady || !fixture.consumer.closed()) delay(10)
            }
            assertFalse(fixture.bus.isReady)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `consumer failure resets readiness and closes consumer`() = runBlocking {
        val fixture = StreamingConsumerFixture()
        try {
            fixture.start()
            fixture.consumer.schedulePollTask { throw IllegalStateException("Consumer terminated") }
            assertTrue(withTimeout(3_000) { fixture.failure.await() } is IllegalStateException)
            withTimeout(3_000) {
                while (fixture.bus.isReady || !fixture.consumer.closed()) delay(10)
            }
            assertFalse(fixture.bus.isReady)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `producer enables idempotence and waits for all replicas`() {
        val properties = producerProperties(
            KafkaConfig(
                brokers = "localhost:9092",
                conversationEventsTopic = "test.conversation-events",
                enabled = false,
            )
        )

        assertEquals("all", properties.getProperty(ProducerConfig.ACKS_CONFIG))
        assertEquals(true, properties[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG])
        assertTrue((properties[ProducerConfig.RETRIES_CONFIG] as Int) > 0)
        assertTrue((properties[ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION] as Int) <= 5)
    }

}

internal fun streamingTestConfig() = KafkaConfig(
    brokers = "localhost:9092",
    conversationEventsTopic = "test.conversation-events",
    enabled = false,
)

internal class StreamingConsumerFixture(
    val consumer: MockConsumer<String, String> = MockConsumer<String, String>("latest"),
) : AutoCloseable {
    val failure = CompletableDeferred<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        failure.complete(error)
    })
    val bus = ConversationEventBus(streamingTestConfig(), consumer)

    suspend fun start() {
        val partition = TopicPartition(streamingTestConfig().conversationEventsTopic, 0)
        consumer.schedulePollTask {
            consumer.updateEndOffsets(mapOf(partition to 0L))
            consumer.rebalance(listOf(partition))
        }
        bus.start(scope)
        withTimeout(3_000) {
            while (!bus.isReady) delay(10)
        }
    }

    override fun close() {
        bus.close()
        scope.cancel()
    }
}
