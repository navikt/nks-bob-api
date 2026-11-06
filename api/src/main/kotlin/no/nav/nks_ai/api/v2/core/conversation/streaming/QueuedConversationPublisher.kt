package no.nav.nks_ai.api.v2.core.conversation.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.conversation.ConversationId
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord

private val publisherLogger = KotlinLogging.logger { }

internal class QueuedConversationPublisher(
    private val producer: Producer<String, String>,
    private val topic: String,
    capacity: Int = 1024,
    private val drainTimeoutMillis: Long = 2_000,
    private val heartbeatIntervalMillis: Long = STREAM_HEARTBEAT_INTERVAL_MILLIS,
    private val heartbeatNowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    init {
        require(heartbeatIntervalMillis > 0)
    }

    val publisherId: String = UUID.randomUUID().toString()
    // Explicitly closed by ConversationEventBus, after application request processing has stopped.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val lastWarning = AtomicLong(0)
    private val lossGeneration = AtomicLong(0)
    private val queue = Channel<ConversationIdEvent>(
        capacity = capacity,
        onUndeliveredElement = { reportFailure("shutdown") },
    )
    private val worker = scope.launch {
        for (envelope in queue) {
            try {
                val payload = Json.encodeToString(envelope)
                val record = ProducerRecord(topic, envelope.conversationId.value.toString(), payload)
                record.headers()
                    .add(STREAM_PUBLISHER_HEADER, publisherId.encodeToByteArray())
                    .add(STREAM_GENERATION_HEADER, requireNotNull(envelope.publisherGeneration).toString().encodeToByteArray())
                runInterruptible {
                    producer.send(record) { _, exception ->
                        if (exception != null) reportFailure("async")
                    }
                }
            } catch (cancelled: CancellationException) {
                reportFailure("shutdown")
                throw cancelled
            } catch (_: Exception) {
                reportFailure("send")
            }
        }
    }

    private val heartbeatWorker = scope.launch {
        while (!closed.get()) {
            try {
                // NoOp keeps the value compatible with consumers deployed before stream leases.
                val envelope = ConversationIdEvent(
                    ConversationId(UUID.fromString(publisherId)), ConversationEvent.NoOp(),
                )
                val record = ProducerRecord(topic, publisherId, Json.encodeToString(envelope))
                record.headers()
                    .add(STREAM_PUBLISHER_HEADER, publisherId.encodeToByteArray())
                    .add(STREAM_GENERATION_HEADER, lossGeneration.get().toString().encodeToByteArray())
                    .add(STREAM_HEARTBEAT_HEADER, heartbeatNowMillis().toString().encodeToByteArray())
                runInterruptible {
                    producer.send(record) { _, exception ->
                        if (exception != null) reportFailure("heartbeat")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportFailure("heartbeat")
            }
            delay(heartbeatIntervalMillis)
        }
    }

    fun publish(conversationId: ConversationId, event: ConversationEvent) {
        if (event is ConversationEvent.NoOp) return
        val result = queue.trySend(ConversationIdEvent(conversationId, event, publisherId, lossGeneration.get()))
        if (result.isFailure) reportFailure(if (result.isClosed) "closed" else "queue_full")
    }

    private fun reportFailure(reason: String) {
        lossGeneration.incrementAndGet()
        MetricRegister.kafkaPublishFailures.labelValues(reason).inc()
        val now = System.nanoTime()
        val previous = lastWarning.get()
        if ((previous == 0L || now - previous >= Duration.ofSeconds(30).toNanos()) &&
            lastWarning.compareAndSet(previous, now)
        ) {
            publisherLogger.warn { "Conversation event not delivered to Kafka ($reason)" }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
        runBlocking {
            heartbeatWorker.cancel()
            val drained = withTimeoutOrNull(drainTimeoutMillis) {
                worker.join()
                true
            } ?: false
            if (!drained) {
                publisherLogger.warn { "Timed out draining Kafka publish queue; discarding remaining events" }
                worker.cancel()
                queue.cancel()
            }
            try {
                producer.close(Duration.ofSeconds(2))
            } catch (_: Exception) {
                reportFailure("close")
            } finally {
                scope.cancel()
            }
            if (withTimeoutOrNull(2_000) { worker.join(); true } != true) {
                publisherLogger.error { "Kafka publisher did not stop within shutdown deadline" }
            }
            if (withTimeoutOrNull(2_000) { heartbeatWorker.join(); true } != true) {
                publisherLogger.error { "Kafka heartbeat publisher did not stop within shutdown deadline" }
            }
        }
    }
}
