package no.nav.nks_ai.api.core.integration

import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.NewMessage

@Serializable
data class IntegrationConversationRequest(
    val navIdent: String,
    val title: String,
    val initialMessage: NewMessage?,
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
