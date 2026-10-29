package no.nav.nks_ai.api.v2.core.conversation.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.KafkaConfig
import no.nav.nks_ai.api.core.conversation.ConversationId
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

private val logger = KotlinLogging.logger { }

/**
 * Envelope published to Kafka for every [ConversationEvent] produced while KBS is answering a
 * question. Every running instance of the API consumes every event (see [ConversationEventBus]),
 * so a websocket connection can be served by any instance regardless of which instance is
 * currently talking to KBS for that conversation.
 */
@Serializable
data class ConversationIdEvent(
    val conversationId: ConversationId,
    val event: ConversationEvent,
)

/**
 * A [ConversationIdEvent] together with the Kafka offset and produce timestamp it was read at.
 * Both are only used locally (never serialized): the offset to de-duplicate between the replayed
 * [ConversationEventBus.history] and the live [ConversationEventBus.events] flow when a websocket
 * connects, and the timestamp to cap how far back [history] replays - see Ws.kt. Since all events
 * for a given conversation are published with the conversation id as the record key, they always
 * land in the same partition, so the offset is a correct, strictly increasing ordering/dedup key
 * for that conversation regardless of which instance produced a given event.
 */
data class ConversationEventRecord(
    val offset: Long,
    val timestamp: Long,
    val idEvent: ConversationIdEvent,
)

/**
 * Publishes [ConversationEvent]s produced by [no.nav.nks_ai.api.v2.core.SendMessageService] to a
 * shared Kafka topic, and re-broadcasts everything read back from that topic as an in-process
 * [SharedFlow] that the websocket endpoint (Ws.kt) filters on conversation id.
 *
 * Each instance subscribes with its own, randomly generated consumer group id. This is a
 * deliberate broadcast/fan-out pattern (not the usual competing-consumers pattern): every running
 * pod must see every event, since we don't know in advance which pod holds the websocket
 * connection for a given conversation.
 *
 * Every instance also keeps a small in-memory replay buffer per conversation (see [history]), so
 * that a websocket connecting late - e.g. because the frontend navigated away and back, or has a
 * slow connection - can be sent everything that happened in the conversation so far, not just
 * events from the moment it connects. The frontend is expected to filter/dedupe on its side if it
 * only cares about events it hasn't already rendered.
 */
class ConversationEventBus internal constructor(
    private val config: KafkaConfig,
    private val consumer: Consumer<String, String>?,
) : AutoCloseable {
    constructor(config: KafkaConfig, instanceId: String = UUID.randomUUID().toString()) : this(
        config,
        if (config.enabled) {
            KafkaConsumer(consumerProperties(config, instanceId), StringDeserializer(), StringDeserializer())
        } else {
            null
        },
    )

    // Lazily created (and entirely absent when config.enabled == false) so that environments
    // without a real broker - most importantly tests spinning up the full application - never
    // open a socket or attempt to talk to Kafka at all.
    private val publisher: QueuedConversationPublisher? =
        if (config.enabled) {
            QueuedConversationPublisher(
                KafkaProducer(producerProperties(config), StringSerializer(), StringSerializer()),
                config.conversationEventsTopic,
            )
        } else {
            null
        }

    private val consumerReadiness = ConsumerReadiness()

    /** Ready only once this instance has an assignment and established its initial offsets. */
    internal val readiness: StateFlow<ConsumerReadinessState> = consumerReadiness.state
    val isReady: Boolean get() = readiness.value.isReady

    private val _events = MutableSharedFlow<ConversationEventRecord>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** All conversation events seen by this instance, regardless of which instance produced them. */
    val events: SharedFlow<ConversationEventRecord> = _events.asSharedFlow()
    internal val eventSubscriberCount: Int get() = _events.subscriptionCount.value

    // Bounded, per-conversation replay buffer. Access-ordered so the least-recently-used
    // conversation is evicted first once we hit MAX_TRACKED_CONVERSATIONS, capping memory use
    // even if many conversations are opened without ever completing.
    private val historyLock = Any()
    private val history =
        object : LinkedHashMap<ConversationId, ArrayDeque<ConversationEventRecord>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ConversationId, ArrayDeque<ConversationEventRecord>>,
            ): Boolean = size > MAX_TRACKED_CONVERSATIONS
        }

    private companion object {
        // A single message/answer cycle is typically well under this many events; this just caps
        // worst-case memory per conversation.
        const val MAX_EVENTS_PER_CONVERSATION = 500

        // Caps total memory across all conversations being tracked at once.
        const val MAX_TRACKED_CONVERSATIONS = 2000

        // Replaying events older than this to a newly connected websocket isn't useful: an answer
        // this old is long finished, and the frontend has almost certainly already fetched the
        // final message via the regular REST endpoints by the time it (re)connects.
        val MAX_HISTORY_AGE: Duration = Duration.ofMinutes(10)
    }

    private var consumerJob: Job? = null
    private val closing = AtomicBoolean(false)
    private val consumerLifecycleLock = Any()
    private var consumerLoopStarted = false

    fun start(scope: CoroutineScope) {
        synchronized(consumerLifecycleLock) {
            check(!closing.get()) { "ConversationEventBus is closed" }
            check(consumerJob == null) { "ConversationEventBus already started" }
            val consumer = consumer ?: run {
                logger.info { "Kafka disabled, ConversationEventBus will not consume any events" }
                return
            }
            consumerJob = scope.launch(Dispatchers.IO) {
                val shouldRun = synchronized(consumerLifecycleLock) {
                    if (closing.get()) {
                        false
                    } else {
                        consumerLoopStarted = true
                        true
                    }
                }
                if (!shouldRun) return@launch

                try {
                    consumer.subscribe(listOf(config.conversationEventsTopic), consumerReadiness)
                    logger.info { "Subscribed to Kafka topic ${config.conversationEventsTopic}" }
                    while (isActive) {
                        val records = try {
                            consumer.poll(Duration.ofSeconds(1))
                        } catch (exception: WakeupException) {
                            if (closing.get()) break else throw exception
                        }
                        consumerReadiness.establishPositions(consumer)
                        for (record in records) {
                            try {
                                val decoded = Json.decodeFromString<ConversationIdEvent>(record.value())
                                val eventRecord = ConversationEventRecord(record.offset(), record.timestamp(), decoded)
                                recordEvent(eventRecord)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (exception: Exception) {
                                logger.warn {
                                    "Failed to decode conversation event from Kafka " +
                                        "(partition ${record.partition()}, offset ${record.offset()}, " +
                                        "${exception::class.simpleName})"
                                }
                            }
                        }
                    }
                } finally {
                    consumerReadiness.stop()
                    closeConsumer(consumer)
                }
            }
        }
    }

    private fun closeConsumer(consumer: Consumer<String, String>) {
        try {
            consumer.close(Duration.ofSeconds(2))
        } catch (exception: Exception) {
            logger.error { "Failed to close Kafka consumer (${exception::class.simpleName})" }
        }
    }

    internal suspend fun recordEvent(eventRecord: ConversationEventRecord) {
        val conversationId = eventRecord.idEvent.conversationId
        synchronized(historyLock) {
            val deque = history.getOrPut(conversationId) { ArrayDeque() }
            deque.addLast(eventRecord)
            if (deque.size > MAX_EVENTS_PER_CONVERSATION) deque.removeFirst()
        }
        _events.emit(eventRecord)
    }

    /**
     * Every event recorded so far for [conversationId] that is younger than [MAX_HISTORY_AGE],
     * oldest first. Used to "catch up" a newly-connected websocket before it starts forwarding
     * [events] live - see Ws.kt for how the two are stitched together without gaps or duplicates.
     */
    fun history(conversationId: ConversationId): List<ConversationEventRecord> {
        val cutoff = System.currentTimeMillis() - MAX_HISTORY_AGE.toMillis()
        val snapshot = synchronized(historyLock) { history[conversationId]?.toList() } ?: emptyList()
        return snapshot.filter { it.timestamp >= cutoff }
    }

    /** Enqueues without waiting for Kafka; delivery failures are logged and counted, not raised. */
    fun publish(conversationId: ConversationId, event: ConversationEvent) {
        publisher?.publish(conversationId, event)
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        consumerReadiness.stop()

        val job = synchronized(consumerLifecycleLock) { consumerJob }
        if (job?.isActive == true) {
            try {
                consumer?.wakeup()
            } catch (exception: Exception) {
                logger.warn { "Failed to wake up Kafka consumer (${exception::class.simpleName})" }
            }
        }
        job?.cancel()
        if (job != null) {
            runBlocking {
                val stopped = withTimeoutOrNull(5_000) {
                    job.join()
                    true
                } ?: false
                if (!stopped) logger.error { "Timed out waiting for Kafka consumer shutdown" }
            }
        }
        val needsClose = synchronized(consumerLifecycleLock) { !consumerLoopStarted }
        if (needsClose) consumer?.let(::closeConsumer)

        publisher?.close()
    }
}

internal data class ConsumerReadinessState(val isReady: Boolean = false, val generation: Long = 0)

internal class ConsumerReadiness : ConsumerRebalanceListener {
    private val mutableState = MutableStateFlow(ConsumerReadinessState())
    val state: StateFlow<ConsumerReadinessState> = mutableState.asStateFlow()
    val isReady: Boolean get() = state.value.isReady
    private var stopped = false

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) = reset()

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) = reset()

    override fun onPartitionsLost(partitions: Collection<TopicPartition>) = reset()

    @Synchronized
    private fun reset() {
        val previous = mutableState.value
        if (!previous.isReady) return
        // A generation change survives StateFlow conflation of a fast revoke/reassignment.
        mutableState.value = ConsumerReadinessState(generation = previous.generation + 1)
    }

    // Called only by the consumer IO loop, never by a websocket or the shutdown thread.
    fun establishPositions(consumer: Consumer<String, String>, timeout: Duration = Duration.ofSeconds(2)) {
        val partitions = consumer.assignment()
        if (partitions.isEmpty()) {
            reset()
            return
        }
        if (isReady) return

        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            for (partition in partitions) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                consumer.position(partition, Duration.ofNanos(remaining))
            }
        } catch (_: TimeoutException) {
            return // Retry on the next poll; assignment alone does not establish a latest offset.
        }
        synchronized(this) {
            if (!stopped) mutableState.value = mutableState.value.copy(isReady = true)
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        reset()
    }
}

private fun commonProperties(config: KafkaConfig): Properties = Properties().apply {
    put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, config.brokers)
    if (config.securityEnabled) {
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        put("ssl.truststore.location", config.truststorePath)
        put("ssl.truststore.password", config.credstorePassword)
        put("ssl.truststore.type", "JKS")
        put("ssl.keystore.location", config.keystorePath)
        put("ssl.keystore.password", config.credstorePassword)
        put("ssl.key.password", config.credstorePassword)
        put("ssl.keystore.type", "PKCS12")
    }
}

internal fun producerProperties(config: KafkaConfig): Properties = commonProperties(config).apply {
    put(ProducerConfig.ACKS_CONFIG, "all")
    put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
    put(ProducerConfig.RETRIES_CONFIG, 5)
    put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
    put(ProducerConfig.LINGER_MS_CONFIG, "5")
}

private fun consumerProperties(config: KafkaConfig, instanceId: String): Properties = commonProperties(config).apply {
    // Unique, ephemeral group per instance: every pod must see every event (fan-out), and we
    // don't care about offsets surviving a restart, only about live traffic from "now on".
    put(ConsumerConfig.GROUP_ID_CONFIG, "nks-bob-api-conversation-events-$instanceId")
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
}
