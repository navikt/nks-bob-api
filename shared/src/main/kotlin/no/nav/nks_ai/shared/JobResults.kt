package no.nav.nks_ai.shared

import kotlinx.serialization.Serializable

@Serializable
data class DeleteOldConversationsSummary(
    val deletedMessages: Int,
    val deletedConversations: Int,
)

@Serializable
data class UploadStarredMessagesSummary(
    val uploadedMessages: Int,
    val errors: List<String>,
)
