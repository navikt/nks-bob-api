package no.nav.nks_ai.api.core.conversation.active

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.core.conversation.ConversationId

@Serializable
data class ActiveConversation(
    val id: ConversationId,
    val title: String,
    val createdAt: LocalDateTime,
    val connectedAt: LocalDateTime,
)

enum class ActiveConversationSort {
    CREATED_AT_ASC,
    CREATED_AT_DESC,
    CONNECTED_AT_ASC,
    CONNECTED_AT_DESC,
}

data class ActiveConversationPageRequest(
    val page: Int = 0,
    val size: Int = 100,
    val sort: ActiveConversationSort = ActiveConversationSort.CREATED_AT_DESC,
)

data class ActiveConversationStatistics(
    val conversations: Long,
    val connections: Long,
    val measuredAtEpochSeconds: Double,
)
