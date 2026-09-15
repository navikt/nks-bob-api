package no.nav.nks_ai.api.core.conversation.active

import io.github.oshai.kotlinlogging.KotlinLogging
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.model.registry.MultiCollector
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.MetricSnapshots
import no.nav.nks_ai.api.app.ApplicationResult
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

class ActiveConversationMetrics(
    private val registry: PrometheusRegistry,
) : AutoCloseable {
    private val latest = AtomicReference<ActiveConversationStatistics?>()
    private val errors = Counter.builder()
        .name("nksbobapi_active_conversations_refresh_errors_total")
        .help("Failed active conversation statistics refreshes")
        .register(registry)

    private val collector = object : MultiCollector {
        override fun getPrometheusNames(): List<String> = listOf(
            "nksbobapi_active_conversations",
            "nksbobapi_active_conversation_connections",
            "nksbobapi_active_conversations_last_success_timestamp_seconds",
        )

        override fun collect(): MetricSnapshots {
            // Read once so one scrape cannot mix counts and timestamps from different refreshes.
            val snapshot = latest.get()
            return MetricSnapshots(
                gauge(
                    "nksbobapi_active_conversations",
                    "Global number of distinct conversations with a valid connection",
                    snapshot?.conversations?.toDouble() ?: Double.NaN,
                ),
                gauge(
                    "nksbobapi_active_conversation_connections",
                    "Global number of valid conversation connections",
                    snapshot?.connections?.toDouble() ?: Double.NaN,
                ),
                gauge(
                    "nksbobapi_active_conversations_last_success_timestamp_seconds",
                    "Unix timestamp of the last successful statistics refresh",
                    snapshot?.measuredAtEpochSeconds ?: 0.0,
                ),
            )
        }
    }

    init {
        registry.register(collector)
    }

    suspend fun refresh(
        getStatistics: suspend () -> ApplicationResult<ActiveConversationStatistics> =
            ActiveConversationStatisticsRepo::getStatistics,
    ) {
        getStatistics().fold(
            ifLeft = {
                errors.inc()
                logger.warn { "Active conversation statistics are stale: refresh failed" }
            },
            ifRight = { latest.set(it) },
        )
    }

    override fun close() {
        registry.unregister(collector)
        registry.unregister(errors)
    }

    private fun gauge(name: String, help: String, value: Double): GaugeSnapshot =
        GaugeSnapshot.builder()
            .name(name)
            .help(help)
            .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(value).build())
            .build()
}
