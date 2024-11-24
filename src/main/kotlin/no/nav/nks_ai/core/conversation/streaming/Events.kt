package no.nav.nks_ai.core.conversation.streaming

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import no.nav.nks_ai.core.message.Citation
import no.nav.nks_ai.core.message.Context
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageId

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
internal sealed class MessageEvent() {
    @Serializable
    @SerialName("NewMessage")
    data class NewMessage(
        val id: MessageId,
        val message: Message
    ) : MessageEvent()

    @Serializable
    @SerialName("ContentUpdated")
    data class ContentUpdated(
        val id: MessageId,
        val content: String,
    ) : MessageEvent()

    @Serializable
    @SerialName("CitationsUpdated")
    data class CitationsUpdated(
        val id: MessageId,
        val citations: List<Citation>,
    ) : MessageEvent()

    @Serializable
    @SerialName("ContextUpdated")
    data class ContextUpdated(
        val id: MessageId,
        val context: List<Context>,
    ) : MessageEvent()

    @Serializable
    @SerialName("PendingUpdated")
    data class PendingUpdated(
        val id: MessageId,
        val message: Message,
        val pending: Boolean,
    ) : MessageEvent()

    class NoOp : MessageEvent()
}

internal fun Message.diff(message: Message): MessageEvent {
    if (this.id != message.id) {
        return MessageEvent.NewMessage(
            id = message.id,
            message = message
        )
    }

    if (this.content != message.content) {
        return MessageEvent.ContentUpdated(
            id = message.id,
            content = message.content.removePrefix(this.content)
        )
    }

    if (this.citations != message.citations) {
        return MessageEvent.CitationsUpdated(
            id = message.id,
            citations = message.citations
        )
    }

    if (this.context != message.context) {
        return MessageEvent.ContextUpdated(
            id = message.id,
            context = message.context
        )
    }

    if (this.pending != message.pending) {
        return MessageEvent.PendingUpdated(
            id = message.id,
            message = message,
            pending = message.pending,
        )
    }

    return MessageEvent.NoOp()
}