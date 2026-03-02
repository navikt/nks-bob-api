package no.nav.nks_ai.api.v2.core.conversation.streaming

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.core.message.Citation
import no.nav.nks_ai.api.core.message.Context
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageError
import no.nav.nks_ai.api.core.message.MessageId

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class ConversationEvent {
    @Serializable
    @SerialName("StatusUpdate")
    data class StatusUpdate(
        val id: MessageId,
        val content: String,
    ) : ConversationEvent()

    @Serializable
    @SerialName("NewMessage")
    data class NewMessage(
        val id: MessageId,
        val message: Message
    ) : ConversationEvent()

    @Serializable
    @SerialName("ContentUpdated")
    data class ContentUpdated(
        val id: MessageId,
        val content: String,
    ) : ConversationEvent()

    @Serializable
    @SerialName("MessageUpdated")
    data class MessageUpdated(
        val id: MessageId,
        val message: Message,
    ) : ConversationEvent()

    @Serializable
    @SerialName("PendingUpdated")
    data class PendingUpdated(
        val id: MessageId,
        val message: Message,
        val pending: Boolean,
    ) : ConversationEvent()

    @Serializable
    @SerialName("ErrorsUpdated")
    data class ErrorsUpdated(
        val id: MessageId,
        val errors: List<MessageError>,
    ) : ConversationEvent()

    @Serializable
    @SerialName("ServerError")
    data class ServerError(
        val message: String,
        val description: String,
    ) : ConversationEvent() {
        constructor(error: ApplicationError) : this(
            message = error.message,
            description = error.description,
        )
    }

    @Serializable
    class NoOp : ConversationEvent()
}
