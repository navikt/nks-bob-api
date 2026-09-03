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
 * Publishes [ConversationEvent]s produced by [no.nav.nks_ai.api.v2.core.SendMessageService] to a
 * shared Kafka topic, and re-broadcasts everything read back from that topic as an in-process
 * [SharedFlow] that the websocket endpoint (ws.kt) filters on conversation id.
 *
 * Each instance subscribes with its own, randomly generated consumer group id. This is a
 * deliberate broadcast/fan-out pattern (not the usual competing-consumers pattern): every running
 * pod must see every event, since we don't know in advance which pod holds the websocket
 * connection for a given conversation.
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

    private val _events = MutableSharedFlow<ConversationIdEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** All conversation events seen by this instance, regardless of which instance produced them. */
    val events: SharedFlow<ConversationIdEvent> = _events.asSharedFlow()

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
                        .onSuccess { _events.emit(it) }
                        .onFailure { logger.warn(it) { "Failed to decode conversation event from Kafka" } }
                }
            }
        }
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
