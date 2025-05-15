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
    private val cache = Caffeine.newBuilder().asCache<String, List<Notification>>()
    private val ALL = "all"
    private val NEWS = "news"
    private val ERRORS = "errors"

    override suspend fun getAllNotifications(): ApplicationResult<List<Notification>> =
        cache.eitherGet(ALL) {
            NotificationRepo.getNotifications()
        }

    override suspend fun getNews(): ApplicationResult<List<NewsNotification>> =
        cache.eitherGet(NEWS) {
            NotificationRepo.getNewsNotifications()
        }.map { it.map(NewsNotification::fromNotification) }

    override suspend fun getErrors(): ApplicationResult<List<ErrorNotification>> =
        cache.eitherGet(ERRORS) {
            NotificationRepo.getErrorNotifications()
        }.map { it.map(ErrorNotification::fromNotification) }

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
            ?: cache.invalidateAll()

        return NotificationRepo.patchNotification(
            notificationId = notificationId,
            expiresAt = Option.fromNullable(notification.expiresAt), // TODO ???
            notificationType = Option.fromNullable(notification.notificationType),
            title = Option.fromNullable(notification.title),
            content = Option.fromNullable(notification.content),
        )
    }

    override suspend fun deleteNotification(notificationId: NotificationId): ApplicationResult<Unit> {
        cache.invalidateAll()
        return NotificationRepo.deleteNotification(notificationId)
    }

    private fun invalidateCaches(type: NotificationType) {
        cache.invalidate(ALL)
        when (type) {
            NotificationType.News -> cache.invalidate(NEWS)
            else -> cache.invalidate(ERRORS)
        }
    }
}