package no.nav.nks_ai.core.conversation.active

import arrow.core.left
import arrow.core.right
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationMetrics
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActiveConversationMetricsTest {
    @Test
    fun `counts are unknown until the first successful refresh`() {
        val registry = PrometheusRegistry()
        ActiveConversationMetrics(registry).use {
            assertTrue(registry.value("nksbobapi_active_conversations").isNaN())
            assertTrue(registry.value("nksbobapi_active_conversation_connections").isNaN())
            assertEquals(0.0, registry.value("nksbobapi_active_conversations_last_success_timestamp_seconds"))
        }
    }

    @Test
    fun `publishes distinct conversations separately from connections`() = runBlocking {
        val registry = PrometheusRegistry()
        ActiveConversationMetrics(registry).use { metrics ->
            metrics.refresh { ActiveConversationStatistics(1, 2, 1000.0).right() }

            assertEquals(1.0, registry.value("nksbobapi_active_conversations"))
            assertEquals(2.0, registry.value("nksbobapi_active_conversation_connections"))
            assertEquals(1000.0, registry.value("nksbobapi_active_conversations_last_success_timestamp_seconds"))
        }
    }

    @Test
    fun `failed refresh preserves the last known snapshot`() = runBlocking {
        val registry = PrometheusRegistry()
        ActiveConversationMetrics(registry).use { metrics ->
            metrics.refresh { ActiveConversationStatistics(2, 3, 1000.0).right() }
            metrics.refresh { ApplicationError.InternalServerError("Database error", "Unavailable").left() }

            assertEquals(2.0, registry.value("nksbobapi_active_conversations"))
            assertEquals(3.0, registry.value("nksbobapi_active_conversation_connections"))
            assertEquals(1000.0, registry.value("nksbobapi_active_conversations_last_success_timestamp_seconds"))
            val errors = registry.scrape().single {
                it.metadata.name == "nksbobapi_active_conversations_refresh_errors"
            }
            assertTrue(errors is CounterSnapshot)
            assertEquals(1.0, errors.dataPoints.single().value)
        }
    }

    @Test
    fun `successful empty snapshot is zero rather than unknown`() = runBlocking {
        val registry = PrometheusRegistry()
        ActiveConversationMetrics(registry).use { metrics ->
            metrics.refresh { ActiveConversationStatistics(0, 0, 2000.0).right() }

            assertEquals(0.0, registry.value("nksbobapi_active_conversations"))
            assertEquals(0.0, registry.value("nksbobapi_active_conversation_connections"))
        }
    }

    @Test
    fun `cancellation is not swallowed as a refresh failure`() = runBlocking {
        val registry = PrometheusRegistry()
        ActiveConversationMetrics(registry).use { metrics ->
            assertFailsWith<CancellationException> {
                metrics.refresh { throw CancellationException("Shutting down") }
            }
        }
    }

    @Test
    fun `closing removes registered metrics`() {
        val registry = PrometheusRegistry()
        ActiveConversationMetrics(registry).close()

        assertEquals(0, registry.scrape().size())
    }

    private fun PrometheusRegistry.value(name: String): Double {
        val snapshot = scrape().single { it.metadata.name == name }
        assertTrue(snapshot is GaugeSnapshot)
        assertTrue(snapshot.dataPoints.single().labels.isEmpty)
        return snapshot.dataPoints.single().value
    }
}
