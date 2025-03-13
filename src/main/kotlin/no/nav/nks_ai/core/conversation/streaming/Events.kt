package no.nav.nks_ai.core.conversation.streaming

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import no.nav.nks_ai.core.message.Citation
import no.nav.nks_ai.core.message.Context
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageError
import no.nav.nks_ai.core.message.MessageId

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class ConversationEvent() {
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
    @SerialName("CitationsUpdated")
    data class CitationsUpdated(
        val id: MessageId,
        val citations: List<Citation>,
    ) : ConversationEvent()

    @Serializable
    @SerialName("ContextUpdated")
    data class ContextUpdated(
        val id: MessageId,
        val context: List<Context>,
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
    )

    @Serializable
    class NoOp : ConversationEvent()
}

fun Message.diff(message: Message): ConversationEvent {
    if (this.id != message.id) {
        return ConversationEvent.NewMessage(
            id = message.id,
            message = message
        )
    }

    if (this.content != message.content) {
        return ConversationEvent.ContentUpdated(
            id = message.id,
            content = message.content.removePrefix(this.content)
        )
    }

    if (this.citations != message.citations) {
        return ConversationEvent.CitationsUpdated(
            id = message.id,
            citations = message.citations
        )
    }

    if (this.context != message.context) {
        return ConversationEvent.ContextUpdated(
            id = message.id,
            context = message.context
        )
    }

    if (this.pending != message.pending) {
        return ConversationEvent.PendingUpdated(
            id = message.id,
            message = message,
            pending = message.pending,
        )
    }

    return ConversationEvent.NoOp()
}