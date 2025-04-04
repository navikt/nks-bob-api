package no.nav.nks_ai.core.notification

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

object NotificationIdSerializer : KSerializer<NotificationId> {
    override fun deserialize(decoder: Decoder): NotificationId {
        return UUID.fromString(decoder.decodeString()).toNotificationId()
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("NotificationId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        messageId: NotificationId
    ) {
        encoder.encodeString(messageId.value.toString())
    }
}

fun UUID.toNotificationId() = NotificationId(this)

fun ApplicationCall.notificationId(name: String = "id"): NotificationId? =
    NotificationId(UUID.fromString(this.parameters[name]))

@Serializable(NotificationIdSerializer::class)
@JvmInline
value class NotificationId(@Contextual val value: UUID)

@Serializable
enum class NotificationType {
    News,
    Error,
}

@Serializable
data class Notification(
    val id: NotificationId,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val notificationType: NotificationType,
    val title: String,
    val content: String
)

@Serializable
data class NewsNotification(
    val id: NotificationId,
    val createdAt: LocalDateTime,
    val title: String,
    val content: String,
)

fun NewsNotification.Companion.fromNotification(notification: Notification) = NewsNotification(
    id = notification.id,
    createdAt = notification.createdAt,
    title = notification.title,
    content = notification.content,
)

@Serializable
data class ErrorNotification(
    val id: NotificationId,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val title: String,
    val content: String,
)

fun ErrorNotification.Companion.fromNotification(notification: Notification) = ErrorNotification(
    id = notification.id,
    createdAt = notification.createdAt,
    expiresAt = notification.expiresAt,
    title = notification.title,
    content = notification.content,
)

@Serializable
data class CreateNotification(
    val expiresAt: LocalDateTime?,
    val notificationType: NotificationType,
    val title: String,
    val content: String
)

@Serializable
data class PatchNotification(
    val expiresAt: LocalDateTime? = null, // TODO how?
    val notificationType: NotificationType? = null,
    val title: String? = null,
    val content: String? = null,
)