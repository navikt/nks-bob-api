package no.nav.nks_ai.api.v2.core.conversation.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.KafkaConfig
import no.nav.nks_ai.api.core.conversation.ConversationId
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
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
 * connects, and the timestamp to cap how far back [history] replays - see ws.kt. Since all events
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
 * [SharedFlow] that the websocket endpoint (ws.kt) filters on conversation id.
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
class ConversationEventBus(
    private val config: KafkaConfig,
    instanceId: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    // Lazily created (and entirely absent when config.enabled == false) so that environments
    // without a real broker - most importantly tests spinning up the full application - never
    // open a socket or attempt to talk to Kafka at all.
    private val producer: KafkaProducer<String, String>? =
        if (config.enabled) KafkaProducer(producerProperties(config), StringSerializer(), StringSerializer()) else null

    private val consumer: KafkaConsumer<String, String>? =
        if (config.enabled) {
            KafkaConsumer(consumerProperties(config, instanceId), StringDeserializer(), StringDeserializer())
        } else {
            null
        }

    private val _events = MutableSharedFlow<ConversationEventRecord>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** All conversation events seen by this instance, regardless of which instance produced them. */
    val events: SharedFlow<ConversationEventRecord> = _events.asSharedFlow()

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

    fun start(scope: CoroutineScope) {
        check(consumerJob == null) { "ConversationEventBus already started" }
        val consumer = consumer ?: run {
            logger.info { "Kafka disabled, ConversationEventBus will not consume any events" }
            return
        }
        consumerJob = scope.launch(Dispatchers.IO) {
            consumer.subscribe(listOf(config.conversationEventsTopic))
            logger.info { "Subscribed to Kafka topic ${config.conversationEventsTopic}" }
            while (isActive) {
                val records = consumer.poll(Duration.ofSeconds(1))
                for (record in records) {
                    runCatching { Json.decodeFromString<ConversationIdEvent>(record.value()) }
                        .onSuccess { decoded ->
                            val eventRecord = ConversationEventRecord(record.offset(), record.timestamp(), decoded)
                            synchronized(historyLock) {
                                val deque = history.getOrPut(decoded.conversationId) { ArrayDeque() }
                                deque.addLast(eventRecord)
                                if (deque.size > MAX_EVENTS_PER_CONVERSATION) deque.removeFirst()
                            }
                            _events.emit(eventRecord)
                        }
                        .onFailure { logger.warn(it) { "Failed to decode conversation event from Kafka" } }
                }
            }
        }
    }

    /**
     * Every event recorded so far for [conversationId] that is younger than [MAX_HISTORY_AGE],
     * oldest first. Used to "catch up" a newly-connected websocket before it starts forwarding
     * [events] live - see ws.kt for how the two are stitched together without gaps or duplicates.
     */
    fun history(conversationId: ConversationId): List<ConversationEventRecord> {
        val cutoff = System.currentTimeMillis() - MAX_HISTORY_AGE.toMillis()
        val snapshot = synchronized(historyLock) { history[conversationId]?.toList() } ?: emptyList()
        return snapshot.filter { it.timestamp >= cutoff }
    }

    /** Publishes an event without blocking the caller; failures are logged, not raised. */
    fun publish(conversationId: ConversationId, event: ConversationEvent) {
        val producer = producer ?: return
        if (event is ConversationEvent.NoOp) return

        val payload = Json.encodeToString(ConversationIdEvent(conversationId, event))
        val record = ProducerRecord(config.conversationEventsTopic, conversationId.value.toString(), payload)
        producer.send(record) { _, exception ->
            if (exception != null) {
                logger.error(exception) { "Failed to publish conversation event to Kafka" }
            }
        }
    }

    override fun close() {
        consumerJob?.cancel()
        // A short explicit timeout: the default close() tries to leave the consumer group and
        // flush the producer, which blocks on network round-trips we may not get (e.g. broker
        // unreachable). We would rather drop in-flight events on shutdown than hang.
        runCatching { consumer?.close(Duration.ofSeconds(2)) }
        runCatching { producer?.close(Duration.ofSeconds(2)) }
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

private fun producerProperties(config: KafkaConfig): Properties = commonProperties(config).apply {
    put(ProducerConfig.ACKS_CONFIG, "1")
    put(ProducerConfig.LINGER_MS_CONFIG, "5")
}

private fun consumerProperties(config: KafkaConfig, instanceId: String): Properties = commonProperties(config).apply {
    // Unique, ephemeral group per instance: every pod must see every event (fan-out), and we
    // don't care about offsets surviving a restart, only about live traffic from "now on".
    put(ConsumerConfig.GROUP_ID_CONFIG, "nks-bob-api-conversation-events-$instanceId")
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
}

