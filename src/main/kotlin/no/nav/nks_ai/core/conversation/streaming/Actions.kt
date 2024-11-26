package no.nav.nks_ai.core.conversation.streaming

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.NewConversation
import no.nav.nks_ai.core.message.NewMessage
import no.nav.nks_ai.core.message.UpdateMessage

@Serializable
internal enum class ConversationActionType {
    NewMessage,
    UpdateMessage,
    CreateConversation,
    SubscribeToConversation,
    UnsubscribeAllConversations,
    Heartbeat,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
internal sealed class ConversationAction {
    abstract val type: ConversationActionType
    protected abstract val data: JsonElement

    @Serializable
    @SerialName("NewMessage")
    data class NewMessageAction(
        override val type: ConversationActionType = ConversationActionType.NewMessage,
        override val data: JsonElement
    ) : ConversationAction() {
        fun getData(): NewMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("UpdateMessage")
    data class UpdateMessageAction(
        override val type: ConversationActionType = ConversationActionType.UpdateMessage,
        override val data: JsonElement
    ) : ConversationAction() {
        fun getData(): UpdateMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("CreateConversation")
    data class CreateConversationAction(
        override val type: ConversationActionType = ConversationActionType.CreateConversation,
        override val data: JsonElement
    ) : ConversationAction() {
        fun getData(): NewConversation = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("SubscribeToConversation")
    data class SubscribeToConversationAction(
        override val type: ConversationActionType = ConversationActionType.SubscribeToConversation,
        override val data: JsonElement
    ) : ConversationAction() {
        fun getData(): SubscribeToConversation = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("UnsubscribeAllConversations")
    data class UnsubscribeAllConversationsAction(
        override val type: ConversationActionType = ConversationActionType.UnsubscribeAllConversations,
        override val data: JsonElement
    ) : ConversationAction()

    @Serializable
    @SerialName("Heartbeat")
    data class HeartbeatAction(
        override val type: ConversationActionType = ConversationActionType.Heartbeat,
        override val data: JsonElement
    ) : ConversationAction() {
        fun getData(): String = Json.decodeFromJsonElement(data)

        fun isPing() = getData() == "ping"
    }
}

data class SubscribeToConversation(
    val conversationId: ConversationId,
)