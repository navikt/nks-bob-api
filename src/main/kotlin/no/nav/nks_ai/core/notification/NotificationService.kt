package no.nav.nks_ai.core.notification

import arrow.core.Option
import no.nav.nks_ai.app.DomainResult

interface NotificationService {
    suspend fun getAllNotifications(): DomainResult<List<Notification>>

    suspend fun getNews(): DomainResult<List<NewsNotification>>

    suspend fun getErrors(): DomainResult<List<ErrorNotification>>

    suspend fun addNotification(notification: CreateNotification): DomainResult<Notification>

    suspend fun getNotification(notificationId: NotificationId): DomainResult<Notification>

    suspend fun updateNotification(
        notificationId: NotificationId,
        notification: CreateNotification
    ): DomainResult<Notification>

    suspend fun patchNotification(
        notificationId: NotificationId,
        notification: PatchNotification
    ): DomainResult<Notification>

    suspend fun deleteNotification(notificationId: NotificationId): DomainResult<Unit>
}

fun notificationService() = object : NotificationService {
    override suspend fun getAllNotifications(): DomainResult<List<Notification>> =
        NotificationRepo.getNotifications()

    override suspend fun getNews(): DomainResult<List<NewsNotification>> =
        NotificationRepo.getNewsNotifications()
            .map { it.map(NewsNotification::fromNotification) }

    override suspend fun getErrors(): DomainResult<List<ErrorNotification>> =
        NotificationRepo.getErrorNotifications()
            .map { it.map(ErrorNotification::fromNotification) }

    override suspend fun addNotification(notification: CreateNotification): DomainResult<Notification> =
        NotificationRepo.addNotification(
            expiresAt = notification.expiresAt,
            notificationType = notification.notificationType,
            title = notification.title,
            content = notification.content,
        )

    override suspend fun getNotification(notificationId: NotificationId): DomainResult<Notification> =
        NotificationRepo.getNotification(notificationId)

    override suspend fun updateNotification(
        notificationId: NotificationId,
        notification: CreateNotification
    ): DomainResult<Notification> {
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
    ): DomainResult<Notification> {
        return NotificationRepo.patchNotification(
            notificationId = notificationId,
            expiresAt = Option.fromNullable(notification.expiresAt), // TODO ???
            notificationType = Option.fromNullable(notification.notificationType),
            title = Option.fromNullable(notification.title),
            content = Option.fromNullable(notification.content),
        )
    }

    override suspend fun deleteNotification(notificationId: NotificationId): DomainResult<Unit> =
        NotificationRepo.deleteNotification(notificationId)
}