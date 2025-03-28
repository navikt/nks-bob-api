package no.nav.nks_ai.core.notification

import arrow.core.None
import arrow.core.Option
import arrow.core.raise.either
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.DomainError
import no.nav.nks_ai.app.DomainResult
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

internal object Notifications : UUIDTable("notifications") {
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val expiresAt = datetime("expires_at").nullable()
    val notificationType = enumeration<NotificationType>("notification_type")
    val title = text("title", eagerLoading = true)
    val content = text("content", eagerLoading = true)
}

internal class NotificationDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<NotificationDAO>(Notifications)

    var createdAt by Notifications.createdAt
    var expiresAt by Notifications.expiresAt
    var notificationType by Notifications.notificationType
    var title by Notifications.title
    var content by Notifications.content
}

internal fun NotificationDAO.toModel() = Notification(
    id = id.value.toNotificationId(),
    expiresAt = expiresAt,
    notificationType = notificationType,
    title = title,
    content = content,
)

object NotificationRepo {
    suspend fun getNotifications(): DomainResult<List<Notification>> =
        suspendTransaction {
            either {
                NotificationDAO.all().map { it.toModel() }
            }
        }

    suspend fun getNewsNotifications(): DomainResult<List<Notification>> =
        getNotifications(NotificationType.News)

    suspend fun getErrorNotifications(): DomainResult<List<Notification>> =
        getNotifications(NotificationType.Error)

    private suspend fun getNotifications(type: NotificationType): DomainResult<List<Notification>> =
        suspendTransaction {
            either {
                NotificationDAO.find {
                    Notifications.notificationType eq type
                }.map { it.toModel() }
            }
        }

    suspend fun getNotification(notificationId: NotificationId): DomainResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.findById(notificationId.value)?.toModel()
                    ?: raise(DomainError.NotificationNotFound(notificationId))
            }
        }

    suspend fun addNotification(
        expiresAt: LocalDateTime?,
        notificationType: NotificationType,
        title: String,
        content: String,
    ): DomainResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.new {
                    this.expiresAt = expiresAt
                    this.notificationType = notificationType
                    this.title = title
                    this.content = content
                }.toModel()
            }
        }

    suspend fun updateNotification(
        notificationId: NotificationId,
        expiresAt: LocalDateTime?,
        notificationType: NotificationType,
        title: String,
        content: String,
    ): DomainResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.findByIdAndUpdate(notificationId.value) {
                    it.expiresAt = expiresAt
                    it.notificationType = notificationType
                    it.title = title
                    it.content = content
                }
                    ?.toModel()
                    ?: raise(DomainError.NotificationNotFound(notificationId))
            }
        }

    suspend fun patchNotification(
        notificationId: NotificationId,
        expiresAt: Option<LocalDateTime> = None,
        notificationType: Option<NotificationType> = None,
        title: Option<String> = None,
        content: Option<String> = None,
    ): DomainResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.findByIdAndUpdate(notificationId.value) { entity ->
                    expiresAt.onSome { entity.expiresAt = it }
                    notificationType.onSome { entity.notificationType = it }
                    title.onSome { entity.title = it }
                    content.onSome { entity.content = it }
                }
                    ?.toModel()
                    ?: raise(DomainError.NotificationNotFound(notificationId))
            }
        }

    suspend fun deleteNotification(notificationId: NotificationId): DomainResult<Unit> =
        suspendTransaction {
            either {
                NotificationDAO.findById(notificationId.value)?.delete()
            }
        }
}