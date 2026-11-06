package no.nav.nks_ai.api.v2.core.conversation.streaming

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.answerFrom
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.Future

class QueuedConversationPublisherTest {
    private val conversationId = ConversationId(UUID.randomUUID())
    private val messageId = MessageId(UUID.randomUUID())

    @Test
    fun `publisher metadata and heartbeat remain compatible with the existing JSON envelope`() {
        val heartbeatSent = CountDownLatch(1)
        val producer = TestProducer(onHeartbeat = { heartbeatSent.countDown() })
        QueuedConversationPublisher(producer, "events").use { publisher ->
            publisher.publish(conversationId, event("content"))
            assertTrue(heartbeatSent.await(3, TimeUnit.SECONDS))
        }
        val records = producer.history()
        assertTrue(records.any { it.headers().lastHeader(STREAM_HEARTBEAT_HEADER) != null })
        assertTrue(records.all { it.headers().lastHeader(STREAM_PUBLISHER_HEADER) != null })
        assertTrue(records.all { it.headers().lastHeader(STREAM_GENERATION_HEADER) != null })
        assertTrue(records.none { "\"publisherId\"" in it.value() || "\"publisherGeneration\"" in it.value() })
        records.forEach { decodeEnvelope(it) }
        assertTrue(
            records.filter { it.headers().lastHeader(STREAM_HEARTBEAT_HEADER) != null }
                .all { decodeEnvelope(it).event is ConversationEvent.NoOp },
        )
    }

    @Test
    fun `dropped final pending update signals resynchronization to other instances without blocking caller`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val signalled = CountDownLatch(1)
        val receivers = List(2) { ConversationStreamHealth(null, {}) }
        val producer = TestProducer(
            onHeartbeat = { heartbeat ->
                receivers.forEach { it.observe(heartbeat) }
                if (heartbeat.generation > 0) signalled.countDown()
            },
        ) { _, _ ->
            entered.countDown()
            release.await()
        }
        val publisher = QueuedConversationPublisher(producer, "events", capacity = 1, heartbeatIntervalMillis = 10)
        try {
            publisher.publish(conversationId, event("first"))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            publisher.publish(conversationId, event("queued"))
            val returned = CountDownLatch(1)
            thread(isDaemon = true) {
                publisher.publish(conversationId, terminalEvent())
                returned.countDown()
            }
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertTrue(signalled.await(3, TimeUnit.SECONDS))
            assertTrue(receivers.all { it.state.value.generation > 0 })
            assertTrue(producer.eventHistory().none { decodeEnvelope(it).event is ConversationEvent.PendingUpdated })
        } finally {
            release.countDown()
            publisher.close()
        }
    }

    @Test
    fun `sync and callback failures of final state are broadcast by the heartbeat`() {
        for (asyncFailure in listOf(false, true)) {
            val signalled = CountDownLatch(1)
            val producer = TestProducer(onHeartbeat = { if (it.generation > 0) signalled.countDown() }) { _, callback ->
                if (asyncFailure) callback.onCompletion(null, IllegalStateException("Unavailable"))
                else throw IllegalStateException("Unavailable")
            }
            QueuedConversationPublisher(producer, "events", heartbeatIntervalMillis = 10).use { publisher ->
                publisher.publish(conversationId, terminalEvent())
                assertTrue(signalled.await(3, TimeUnit.SECONDS), "Final-state loss was not signalled")
            }
        }
    }

    @Test
    fun `blocked Kafka send does not block publishing and queue stays bounded`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producer = TestProducer { _, _ ->
            entered.countDown()
            release.await()
        }
        val publisher = QueuedConversationPublisher(producer, "events", capacity = 1)
        try {
            publisher.publish(conversationId, event("first"))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val before = failures("queue_full")
            val published = CountDownLatch(1)
            val caller = thread(isDaemon = true) {
                publisher.publish(conversationId, event("queued"))
                publisher.publish(conversationId, event("overflow"))
                published.countDown()
            }
            assertTrue(published.await(1, TimeUnit.SECONDS), "publish waited for the blocked producer")
            caller.join(1_000)
            assertEquals(before + 1, failures("queue_full"))
        } finally {
            release.countDown()
            publisher.close()
        }
        assertEquals(listOf("first", "queued"), producer.eventHistory().map { decode(it).content })
    }

    @Test
    fun `synchronous producer failure is contained and worker sends next event`() {
        val attempts = AtomicInteger()
        val producer = TestProducer { _, _ ->
            if (attempts.getAndIncrement() == 0) throw IllegalStateException("private upstream detail")
        }
        val before = failures("send")
        QueuedConversationPublisher(producer, "events").use { publisher ->
            publisher.publish(conversationId, event("fails"))
            publisher.publish(conversationId, event("next"))
        }
        assertEquals(2, attempts.get())
        assertEquals(before + 1, failures("send"))
        assertEquals(listOf("next"), producer.eventHistory().map { decode(it).content })
    }

    @Test
    fun `callback failure is counted without stopping publication`() {
        val producer = TestProducer { _, callback ->
            callback.onCompletion(null, IllegalStateException("private upstream detail"))
        }
        val before = failures("async")
        QueuedConversationPublisher(producer, "events").use { publisher ->
            publisher.publish(conversationId, event("first"))
            publisher.publish(conversationId, event("second"))
        }
        assertEquals(before + 2, failures("async"))
        assertEquals(2, producer.eventHistory().size)
    }

    @Test
    fun `close drains accepted events in order and rejects subsequent events`() {
        val producer = TestProducer()
        val publisher = QueuedConversationPublisher(producer, "events")
        repeat(10) { publisher.publish(conversationId, event("$it")) }
        publisher.close()
        publisher.close()
        val before = failures("closed")
        publisher.publish(conversationId, event("too late"))
        assertEquals(before + 1, failures("closed"))
        assertEquals((0..9).map { "$it" }, producer.eventHistory().map { decode(it).content })
        assertTrue(producer.closed())
        assertTrue(producer.eventHistory().all { it.key() == conversationId.value.toString() })
    }

    @Test
    fun `close interrupts blocked send and discards queued events within deadline`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val producer = TestProducer { _, _ ->
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } finally {
                interrupted.countDown()
            }
        }
        val publisher = QueuedConversationPublisher(producer, "events", drainTimeoutMillis = 50)
        val before = failures("shutdown")
        publisher.publish(conversationId, event("blocked"))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        publisher.publish(conversationId, event("queued"))
        val closed = CountDownLatch(1)
        thread(isDaemon = true) {
            publisher.close()
            closed.countDown()
        }
        assertTrue(closed.await(3, TimeUnit.SECONDS), "shutdown exceeded its deadline")
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        assertEquals(before + 2, failures("shutdown"))
        assertTrue(producer.closed())
    }

    @Test
    fun `application NoOp events are not published as data`() {
        val producer = TestProducer()
        QueuedConversationPublisher(producer, "events").use { publisher ->
            publisher.publish(conversationId, ConversationEvent.NoOp())
        }
        assertTrue(producer.eventHistory().isEmpty())
    }

    private fun event(content: String) = ConversationEvent.ContentUpdated(messageId, content)

    private fun terminalEvent(): ConversationEvent.PendingUpdated {
        val message = Message.answerFrom(
            messageId, "Finished", emptyList(), emptyMap(), emptyList(), pending = false,
            userQuestion = null, contextualizedQuestion = null, tools = emptyList(),
            thinking = emptyList(), model = null,
        )
        return ConversationEvent.PendingUpdated(messageId, message, false)
    }

    private fun failures(reason: String) = MetricRegister.kafkaPublishFailures.labelValues(reason).get()

    private fun decode(record: ProducerRecord<String, String>): ConversationEvent.ContentUpdated =
        decodeEnvelope(record).event as ConversationEvent.ContentUpdated

    private fun decodeEnvelope(record: ProducerRecord<String, String>) =
        Json.decodeFromString<ConversationIdEvent>(record.value())

    private class TestProducer(
        private val onHeartbeat: (ConversationStreamHeartbeat) -> Unit = {},
        private val beforeSend: (ProducerRecord<String, String>, Callback) -> Unit = { _, _ -> },
    ) : MockProducer<String, String>(true, null, StringSerializer(), StringSerializer()) {
        override fun send(record: ProducerRecord<String, String>, callback: Callback): Future<RecordMetadata> {
            val isEvent = record.headers().lastHeader(STREAM_HEARTBEAT_HEADER) == null
            if (isEvent) beforeSend(record, callback)
            val result = super.send(record, callback)
            if (!isEvent) {
                onHeartbeat(
                    ConversationStreamHeartbeat(
                        record.headers().lastHeader(STREAM_PUBLISHER_HEADER).value().decodeToString(),
                        record.headers().lastHeader(STREAM_GENERATION_HEADER).value().decodeToString().toLong(),
                        record.headers().lastHeader(STREAM_HEARTBEAT_HEADER).value().decodeToString().toLong(),
                    ),
                )
            }
            return result
        }

        fun eventHistory() = history().filter { it.headers().lastHeader(STREAM_HEARTBEAT_HEADER) == null }
    }
}
