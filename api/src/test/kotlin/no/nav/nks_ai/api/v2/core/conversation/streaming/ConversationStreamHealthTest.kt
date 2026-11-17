package no.nav.nks_ai.api.v2.core.conversation.streaming

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationStreamHealthTest {
    @Test
    fun `local lease requires a consumed heartbeat and expires without broker delivery`() {
        val clock = Clock()
        val local = UUID.randomUUID().toString()
        var invalidations = 0
        val health = ConversationStreamHealth(local, { invalidations++ }, clock::millis, clock::nanos, 100)
        assertFalse(health.state.value.isHealthy)
        health.observePublisher(local, 0)
        assertFalse(health.state.value.isHealthy, "Data alone must not establish a heartbeat lease")
        health.observe(ConversationStreamHeartbeat(local, 0, clock.millis()))
        assertTrue(health.state.value.isHealthy)
        clock.advance(100)
        health.expire()
        assertFalse(health.state.value.isHealthy)
        assertEquals(1, invalidations)
        health.expire()
        assertEquals(1, invalidations)
        health.observe(ConversationStreamHeartbeat(local, 0, clock.millis()))
        assertTrue(health.state.value.isHealthy)
        assertEquals(1, health.state.value.generation)
    }

    @Test
    fun `remote publication loss invalidates both instances even when local leases stay healthy`() {
        val clock = Clock()
        val source = UUID.randomUUID().toString()
        val peers = List(2) { UUID.randomUUID().toString() }
        val health = peers.map { ConversationStreamHealth(it, {}, clock::millis, clock::nanos, 100) }
        health.forEachIndexed { index, tracker ->
            tracker.observe(ConversationStreamHeartbeat(peers[index], 0, clock.millis()))
            tracker.observe(ConversationStreamHeartbeat(source, 0, clock.millis()))
        }
        health.forEach { it.observe(ConversationStreamHeartbeat(source, 1, clock.millis())) }
        assertTrue(health.all { it.state.value.isHealthy && it.state.value.generation == 1L })
        health.forEach { it.observe(ConversationStreamHeartbeat(source, 1, clock.millis())) }
        assertTrue(health.all { it.state.value.generation == 1L }, "Repeated signal must be idempotent")
        clock.advance(100)
        health.forEachIndexed { index, tracker ->
            tracker.observe(ConversationStreamHeartbeat(peers[index], 0, clock.millis()))
            tracker.expire()
        }
        assertTrue(health.all { it.state.value.isHealthy && it.state.value.generation == 2L })
    }

    @Test
    fun `stale heartbeats and records cannot undo a newer loss generation`() {
        val clock = Clock()
        val source = UUID.randomUUID().toString()
        val health = ConversationStreamHealth(source, {}, clock::millis, clock::nanos, 100)
        health.observe(ConversationStreamHeartbeat(source, 0, clock.millis()))
        health.observe(ConversationStreamHeartbeat(source, 1, clock.millis()))
        assertFalse(health.observePublisher(source, 0))
        clock.advance(99)
        health.observe(ConversationStreamHeartbeat(source, 0, clock.millis()))
        clock.advance(1)
        health.expire()
        assertFalse(health.state.value.isHealthy)
        assertFalse(health.observePublisher(source, 0), "Expiry must not forget the publisher's generation")
        assertFalse(health.observePublisher(source, 1), "Data must not revive an expired publisher")
        health.observe(ConversationStreamHeartbeat(source, 1, clock.millis() - 100))
        assertFalse(health.state.value.isHealthy, "An old heartbeat must not revive an expired lease")
    }

    @Test
    fun `delayed heartbeat has only its remaining lease lifetime`() {
        val clock = Clock()
        val source = UUID.randomUUID().toString()
        val health = ConversationStreamHealth(source, {}, clock::millis, clock::nanos, 100)
        health.observe(ConversationStreamHeartbeat(source, 0, clock.millis() - 90))
        assertTrue(health.state.value.isHealthy)
        clock.advance(10)
        health.expire()
        assertFalse(health.state.value.isHealthy)
    }

    @Test
    fun `publisher tracking is bounded and eviction signals resynchronization`() {
        val clock = Clock()
        val health = ConversationStreamHealth(null, {}, clock::millis, clock::nanos, maxPublishers = 1)
        health.observePublisher(UUID.randomUUID().toString(), 0)
        health.observePublisher(UUID.randomUUID().toString(), 0)
        assertEquals(1, health.state.value.generation)
    }

    private class Clock {
        private var elapsed = 1_000L
        fun millis() = elapsed
        fun nanos() = elapsed * 1_000_000
        fun advance(millis: Long) { elapsed += millis }
    }
}
