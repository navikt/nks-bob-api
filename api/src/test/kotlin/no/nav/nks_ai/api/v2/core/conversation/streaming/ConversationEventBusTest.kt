package no.nav.nks_ai.api.v2.core.conversation.streaming

import no.nav.nks_ai.api.app.KafkaConfig
import org.apache.kafka.clients.producer.ProducerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationEventBusTest {
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
