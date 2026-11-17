package no.nav.nks_ai.api.v2.core.conversation.streaming

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nav.nks_ai.api.app.MetricRegister

internal const val STREAM_HEARTBEAT_INTERVAL_MILLIS = 5_000L
internal const val STREAM_HEARTBEAT_LEASE_MILLIS = 30_000L
internal const val STREAM_PUBLISHER_HEADER = "nks-bob-publisher"
internal const val STREAM_GENERATION_HEADER = "nks-bob-generation"
internal const val STREAM_HEARTBEAT_HEADER = "nks-bob-heartbeat"

internal data class ConversationStreamHeartbeat(
    val publisherId: String,
    val generation: Long,
    val sentAt: Long,
)

internal data class ConversationStreamHealthState(val isHealthy: Boolean, val generation: Long = 0)

internal class ConversationStreamHealth(
    private val localPublisherId: String?,
    private val onInvalidated: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nowNanos: () -> Long = System::nanoTime,
    private val leaseMillis: Long = STREAM_HEARTBEAT_LEASE_MILLIS,
    private val maxPublishers: Int = 512,
) {
    private data class Publisher(
        var generation: Long?,
        var lastHeartbeat: Long,
        var heartbeatReceived: Boolean = false,
        var expired: Boolean = false,
    )

    private val publishers = mutableMapOf<String, Publisher>()
    private val mutableState = MutableStateFlow(ConversationStreamHealthState(localPublisherId == null))
    val state = mutableState.asStateFlow()

    init {
        require(leaseMillis > 0 && maxPublishers > 0)
    }

    @Synchronized
    fun observePublisher(publisherId: String?, generation: Long?): Boolean {
        if (publisherId == null) return true
        UUID.fromString(publisherId)
        require(generation == null || generation >= 0)
        val publisher = publishers[publisherId]
        if (publisher == null) {
            if (generation != null && generation != 0L) invalidate("publication_loss")
            addPublisher(publisherId, Publisher(generation, nowNanos()))
        } else if (generation != null) {
            val previous = publisher.generation
            if (previous != null && generation < previous) return false
            if (generation != previous && (previous != null || generation != 0L)) {
                invalidate("publication_loss")
            }
            publisher.generation = generation
        }
        updateHealth()
        return publisher?.expired != true
    }

    @Synchronized
    fun observe(heartbeat: ConversationStreamHeartbeat) {
        UUID.fromString(heartbeat.publisherId)
        require(heartbeat.generation >= 0)
        val age = nowMillis() - heartbeat.sentAt
        if (age >= leaseMillis || age < -leaseMillis) return
        val publisher = publishers[heartbeat.publisherId]
        if (publisher == null) {
            if (heartbeat.generation != 0L) invalidate("publication_loss")
            addPublisher(
                heartbeat.publisherId,
                Publisher(heartbeat.generation, nowNanos() - age.coerceAtLeast(0) * 1_000_000, true),
            )
        } else {
            val previous = publisher.generation
            if (previous != null && heartbeat.generation < previous) return
            if (publisher.generation != heartbeat.generation &&
                (publisher.generation != null || heartbeat.generation != 0L)
            ) {
                invalidate("publication_loss")
            }
            publisher.generation = heartbeat.generation
            publisher.heartbeatReceived = true
            publisher.expired = false
            // A delayed heartbeat cannot renew a lease for another full interval.
            publisher.lastHeartbeat = nowNanos() - age.coerceAtLeast(0) * 1_000_000
        }
        updateHealth()
    }

    @Synchronized
    fun expire() {
        val now = nowNanos()
        val expired = publishers.values.filter {
            !it.expired && now - it.lastHeartbeat >= leaseMillis * 1_000_000
        }
        if (expired.isNotEmpty()) {
            expired.forEach {
                it.expired = true
                it.heartbeatReceived = false
            }
            invalidate("lease_expired")
            updateHealth()
        }
    }

    @Synchronized
    fun invalidate(reason: String) {
        MetricRegister.kafkaStreamInvalidations.labelValues(reason).inc()
        onInvalidated()
        mutableState.value = mutableState.value.copy(generation = mutableState.value.generation + 1)
    }

    private fun addPublisher(id: String, publisher: Publisher) {
        if (publishers.size >= maxPublishers) {
            publishers.clear()
            invalidate("publisher_capacity")
        }
        publishers[id] = publisher
    }

    private fun updateHealth() {
        mutableState.value = mutableState.value.copy(
            isHealthy = localPublisherId == null || publishers[localPublisherId]?.heartbeatReceived == true,
        )
    }
}
