package no.nav.nks_ai.api.core.notification

import arrow.core.None
import arrow.core.Option
import arrow.core.raise.either
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.BaseEntity
import no.nav.nks_ai.api.app.BaseEntityClass
import no.nav.nks_ai.api.app.BaseTable
import no.nav.nks_ai.api.app.suspendTransaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.datetime
import java.util.*

internal object Notifications : BaseTable("notifications") {
    val expiresAt = datetime("expires_at").nullable()
    val notificationType = enumeration<NotificationType>("notification_type")
    val title = text("title", eagerLoading = true)
    val content = text("content", eagerLoading = true)
}

internal class NotificationDAO(id: EntityID<UUID>) : BaseEntity(id, Notifications) {
    companion object : BaseEntityClass<NotificationDAO>(Notifications)

    var expiresAt by Notifications.expiresAt
    var notificationType by Notifications.notificationType
    var title by Notifications.title
    var content by Notifications.content
}

internal fun NotificationDAO.toModel() = Notification(
    id = id.value.toNotificationId(),
    createdAt = createdAt,
    expiresAt = expiresAt,
    notificationType = notificationType,
    title = title,
    content = content,
)

object NotificationRepo {
    suspend fun getNotifications(): ApplicationResult<List<Notification>> =
        suspendTransaction {
            either {
                NotificationDAO.all().map { it.toModel() }
            }
        }

    suspend fun getNewsNotifications(): ApplicationResult<List<Notification>> =
        getNotifications(NotificationType.News)

    suspend fun getErrorNotifications(): ApplicationResult<List<Notification>> =
        either {
            val errorNotifications = getNotifications(NotificationType.Error).bind()
            val warningNotifications = getNotifications(NotificationType.Warning).bind()

            (errorNotifications + warningNotifications).sortedByDescending { it.createdAt }
        }

    private suspend fun getNotifications(type: NotificationType): ApplicationResult<List<Notification>> =
        suspendTransaction {
            either {
                NotificationDAO.find {
                    Notifications.notificationType eq type
                }
                    .map(NotificationDAO::toModel)
                    .sortedByDescending(Notification::createdAt)
            }
        }

    suspend fun getNotification(notificationId: NotificationId): ApplicationResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.findById(notificationId.value)?.toModel()
                    ?: raise(ApplicationError.NotificationNotFound(notificationId))
            }
        }

    suspend fun addNotification(
        expiresAt: LocalDateTime?,
        notificationType: NotificationType,
        title: String,
        content: String,
    ): ApplicationResult<Notification> =
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
    ): ApplicationResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.findByIdAndUpdate(notificationId.value) {
                    it.expiresAt = expiresAt
                    it.notificationType = notificationType
                    it.title = title
                    it.content = content
                }
                    ?.toModel()
                    ?: raise(ApplicationError.NotificationNotFound(notificationId))
            }
        }

    suspend fun patchNotification(
        notificationId: NotificationId,
        expiresAt: Option<LocalDateTime> = None,
        notificationType: Option<NotificationType> = None,
        title: Option<String> = None,
        content: Option<String> = None,
    ): ApplicationResult<Notification> =
        suspendTransaction {
            either {
                NotificationDAO.findByIdAndUpdate(notificationId.value) { entity ->
                    expiresAt.onSome { entity.expiresAt = it }
                    notificationType.onSome { entity.notificationType = it }
                    title.onSome { entity.title = it }
                    content.onSome { entity.content = it }
                }
                    ?.toModel()
                    ?: raise(ApplicationError.NotificationNotFound(notificationId))
            }
        }

    suspend fun deleteNotification(notificationId: NotificationId): ApplicationResult<Unit> =
        suspendTransaction {
            either {
                NotificationDAO.findById(notificationId.value)?.delete()
            }
        }
}