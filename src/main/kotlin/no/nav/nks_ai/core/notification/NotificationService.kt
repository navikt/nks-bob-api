package no.nav.nks_ai.core.notification

import arrow.core.Option
import no.nav.nks_ai.app.ApplicationResult

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
    override suspend fun getAllNotifications(): ApplicationResult<List<Notification>> =
        NotificationRepo.getNotifications()

    override suspend fun getNews(): ApplicationResult<List<NewsNotification>> =
        NotificationRepo.getNewsNotifications()
            .map { it.map(NewsNotification::fromNotification) }

    override suspend fun getErrors(): ApplicationResult<List<ErrorNotification>> =
        NotificationRepo.getErrorNotifications()
            .map { it.map(ErrorNotification::fromNotification) }

    override suspend fun addNotification(notification: CreateNotification): ApplicationResult<Notification> =
        NotificationRepo.addNotification(
            expiresAt = notification.expiresAt,
            notificationType = notification.notificationType,
            title = notification.title,
            content = notification.content,
        )

    override suspend fun getNotification(notificationId: NotificationId): ApplicationResult<Notification> =
        NotificationRepo.getNotification(notificationId)

    override suspend fun updateNotification(
        notificationId: NotificationId,
        notification: CreateNotification
    ): ApplicationResult<Notification> {
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
        return NotificationRepo.patchNotification(
            notificationId = notificationId,
            expiresAt = Option.fromNullable(notification.expiresAt), // TODO ???
            notificationType = Option.fromNullable(notification.notificationType),
            title = Option.fromNullable(notification.title),
            content = Option.fromNullable(notification.content),
        )
    }

    override suspend fun deleteNotification(notificationId: NotificationId): ApplicationResult<Unit> =
        NotificationRepo.deleteNotification(notificationId)
}