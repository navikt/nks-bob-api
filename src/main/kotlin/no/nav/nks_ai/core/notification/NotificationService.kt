package no.nav.nks_ai.core.notification

import arrow.core.Option
import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.asCache
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.eitherGet

interface NotificationService {
    suspend fun getAllNotifications(): ApplicationResult<List<Notification>>

    suspend fun getNews(): ApplicationResult<List<NewsNotification>>

    suspend fun getErrors(): ApplicationResult<List<ErrorNotification>>

    suspend fun addNotification(notification: CreateNotification): ApplicationResult<Notification>

    suspend fun getNotification(notificationId: NotificationId): ApplicationResult<Notification>

    suspend fun updateNotification(
        notificationId: NotificationId,
        notification: CreateNotification
    ): ApplicationResult<Notification>

    suspend fun patchNotification(
        notificationId: NotificationId,
        notification: PatchNotification
    ): ApplicationResult<Notification>

    suspend fun deleteNotification(notificationId: NotificationId): ApplicationResult<Unit>
}

fun notificationService() = object : NotificationService {
    private val notificationsCache = Caffeine.newBuilder().asCache<String, List<Notification>>()
    private val newsCache = Caffeine.newBuilder().asCache<String, List<NewsNotification>>()
    private val errorCache = Caffeine.newBuilder().asCache<String, List<ErrorNotification>>()

    override suspend fun getAllNotifications(): ApplicationResult<List<Notification>> =
        notificationsCache.eitherGet("") {
            NotificationRepo.getNotifications()
        }

    override suspend fun getNews(): ApplicationResult<List<NewsNotification>> =
        newsCache.eitherGet("") {
            NotificationRepo.getNewsNotifications()
                .map { it.map(NewsNotification::fromNotification) }
        }

    override suspend fun getErrors(): ApplicationResult<List<ErrorNotification>> =
        errorCache.eitherGet("") {
            NotificationRepo.getErrorNotifications()
                .map { it.map(ErrorNotification::fromNotification) }
        }

    override suspend fun addNotification(notification: CreateNotification): ApplicationResult<Notification> {
        invalidateCaches(notification.notificationType)
        return NotificationRepo.addNotification(
            expiresAt = notification.expiresAt,
            notificationType = notification.notificationType,
            title = notification.title,
            content = notification.content,
        )
    }

    override suspend fun getNotification(notificationId: NotificationId): ApplicationResult<Notification> =
        NotificationRepo.getNotification(notificationId)

    override suspend fun updateNotification(
        notificationId: NotificationId,
        notification: CreateNotification
    ): ApplicationResult<Notification> {
        invalidateCaches(notification.notificationType)
        return NotificationRepo.updateNotification(
            notificationId = notificationId,
            expiresAt = notification.expiresAt,
            notificationType = notification.notificationType,
            title = notification.title,
            content = notification.content,
        )
    }

    override suspend fun patchNotification(
        notificationId: NotificationId,
        notification: PatchNotification
    ): ApplicationResult<Notification> {
        notification.notificationType
            ?.let(::invalidateCaches)
            ?: invalidateAll()

        return NotificationRepo.patchNotification(
            notificationId = notificationId,
            expiresAt = Option.fromNullable(notification.expiresAt), // TODO ???
            notificationType = Option.fromNullable(notification.notificationType),
            title = Option.fromNullable(notification.title),
            content = Option.fromNullable(notification.content),
        )
    }

    override suspend fun deleteNotification(notificationId: NotificationId): ApplicationResult<Unit> {
        invalidateAll()
        return NotificationRepo.deleteNotification(notificationId)
    }

    private fun invalidateCaches(type: NotificationType) {
        notificationsCache.invalidate("")
        when (type) {
            NotificationType.News -> newsCache.invalidate("")
            else -> errorCache.invalidate("")
        }
    }

    private fun invalidateAll() {
        notificationsCache.invalidate("")
        newsCache.invalidate("")
        errorCache.invalidate("")
    }
}