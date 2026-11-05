package no.nav.nks_ai.api.v2.core.conversation.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.user.NavIdent

private val logger = KotlinLogging.logger {}

class ConversationNotificationHub(
    private val eventBus: ConversationEventBus,
    private val loadOwnerHash: suspend (ConversationId) -> ApplicationResult<String>,
    private val maxConnections: Int = 512,
    private val maxConnectionsPerUser: Int = 5,
    private val bufferCapacity: Int = 32,
) : AutoCloseable {
    private class UserSessions(
        val owner: NavIdent,
        val subscriptions: MutableMap<Channel<ConversationId>, Long> = mutableMapOf(),
    )

    private val lock = Any()
    private val users = mutableMapOf<String, UserSessions>()
    private var connections = 0
    private var job: Job? = null
    private var supervisor: Job? = null
    private var closed = false

    init {
        require(maxConnections > 0 && maxConnectionsPerUser > 0 && bufferCapacity > 0)
    }

    internal val subscriptionCount: Int get() = synchronized(lock) { connections }

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            check(!closed && job == null) { "Notification hub already started or closed" }
            val handler = CoroutineExceptionHandler { _, cause ->
                logger.error { "Notification dispatcher failed (${cause::class.simpleName})" }
                failSubscribers("dispatcher")
            }
            val supervisor = SupervisorJob(scope.coroutineContext[Job]).also { this.supervisor = it }
            var lastSequence = eventBus.latestCreationSequence
            job = scope.launch(Dispatchers.IO + supervisor + handler, start = CoroutineStart.UNDISPATCHED) {
                eventBus.creations.collect { record ->
                    if (record.sequence != lastSequence + 1) {
                        failSubscribers("dispatcher_overflow")
                    }
                    lastSequence = record.sequence
                    dispatch(record)
                }
            }
        }
    }

    internal fun subscribe(owner: NavIdent): Subscription? = synchronized(lock) {
        val key = owner.plaintext.value
        val existing = users[key]
        if (closed || job?.isActive != true || connections >= maxConnections ||
            (existing?.subscriptions?.size ?: 0) >= maxConnectionsPerUser
        ) {
            return@synchronized null
        }
        val user = existing ?: UserSessions(owner).also { users[key] = it }
        val channel = Channel<ConversationId>(bufferCapacity)
        // Exclude events received before this connection, even if dispatch is still queued.
        user.subscriptions[channel] = eventBus.latestCreationSequence
        connections++
        Subscription(channel) {
            synchronized(lock) {
                if (user.subscriptions.remove(channel) != null) {
                    connections--
                    if (user.subscriptions.isEmpty()) users.remove(key)
                }
            }
            channel.cancel()
        }
    }

    private suspend fun dispatch(record: ConversationCreationRecord) {
        val conversationId = record.conversationId
        val recipients = synchronized(lock) {
            users.values.mapNotNull { user ->
                val channels = user.subscriptions.filterValues { it < record.sequence }.keys.toList()
                if (channels.isEmpty()) null else user.owner to channels
            }
        }
        if (recipients.isEmpty()) return
        loadOwnerHash(conversationId).fold(
            ifLeft = { error ->
                if (error !is ApplicationError.ConversationNotFound) {
                    logger.error { "Could not verify notification ownership" }
                    failSubscribers("ownership")
                }
            },
            ifRight = { hash ->
                for ((owner, channels) in recipients) {
                    if (owner.isVerified(hash)) {
                        for (channel in channels) {
                            val sent = channel.trySend(conversationId)
                            if (sent.isFailure && !sent.isClosed) {
                                MetricRegister.notificationStreamFailures.labelValues("overflow").inc()
                                channel.cancel(NotificationStreamUnavailable())
                            }
                        }
                    }
                }
            },
        )
    }

    private fun failSubscribers(reason: String) {
        MetricRegister.notificationStreamFailures.labelValues(reason).inc()
        val channels = synchronized(lock) { users.values.flatMap { it.subscriptions.keys.toList() } }
        channels.forEach { it.cancel(NotificationStreamUnavailable()) }
    }

    override fun close() {
        val dispatcher = synchronized(lock) {
            if (closed) return
            closed = true
            supervisor
        }
        dispatcher?.cancel()
        val channels = synchronized(lock) { users.values.flatMap { it.subscriptions.keys.toList() } }
        channels.forEach { it.cancel(NotificationStreamUnavailable()) }
    }

    internal class Subscription(
        val events: Channel<ConversationId>,
        private val release: () -> Unit,
    ) : AutoCloseable {
        override fun close() = release()
    }
}

internal class NotificationStreamUnavailable : CancellationException("Notification stream unavailable")
