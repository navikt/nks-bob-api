package no.nav.nks_ai.core.conversation.streaming

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.core.message.UpdateMessage

@Serializable
internal enum class MessageActionType {
    NewMessage,
    UpdateMessage,
    Heartbeat,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
internal sealed class MessageAction {
    abstract val type: MessageActionType
    protected abstract val data: JsonElement

    @Serializable
    @SerialName("NewMessage")
    data class NewMessageAction(
        override val type: MessageActionType = MessageActionType.NewMessage,
        override val data: JsonElement
    ) : MessageAction() {
        fun getData(): NewMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("UpdateMessage")
    data class UpdateMessageAction(
        override val type: MessageActionType = MessageActionType.UpdateMessage,
        override val data: JsonElement
    ) : MessageAction() {
        fun getData(): UpdateMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("Heartbeat")
    data class HeartbeatAction(
        override val type: MessageActionType = MessageActionType.Heartbeat,
        override val data: JsonElement
    ) : MessageAction() {
        fun getData(): String = Json.decodeFromJsonElement(data)

        fun isPing() = getData() == "ping"
    }
}

