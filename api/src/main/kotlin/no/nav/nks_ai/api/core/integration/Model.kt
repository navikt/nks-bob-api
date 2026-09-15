package no.nav.nks_ai.api.core.integration

import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.message.MessageId

@Serializable
data class AcceptedIntegrationMessage(
    val conversationId: ConversationId,
    val messageId: MessageId,
)

@Serializable
data class IntegrationProblem(
    val title: String,
    val status: Int,
    val detail: String,
    val type: String = "about:blank",
    val conversationId: ConversationId? = null,
    val messageId: MessageId? = null,
)
