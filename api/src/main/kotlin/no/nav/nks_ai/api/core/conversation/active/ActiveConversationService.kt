package no.nav.nks_ai.api.core.conversation.active

import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.user.NavIdent
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface ActiveConversationService {
    suspend fun connect(conversationId: ConversationId, owner: NavIdent): ApplicationResult<UUID>

    suspend fun renew(connectionId: UUID): ApplicationResult<Unit>

    suspend fun disconnect(connectionId: UUID): ApplicationResult<Unit>

    suspend fun getActiveConversations(
        owner: NavIdent,
        request: ActiveConversationPageRequest,
    ): ApplicationResult<Page<ActiveConversation>>

    suspend fun getStatistics(): ApplicationResult<ActiveConversationStatistics>

    suspend fun deleteExpiredConnections(): ApplicationResult<Int>

    companion object {
        val RENEW_INTERVAL = 60.seconds
        val LEASE_DURATION = 5.minutes

        fun create(): ActiveConversationService = DefaultActiveConversationService()
    }
}

private class DefaultActiveConversationService : ActiveConversationService {
    override suspend fun connect(conversationId: ConversationId, owner: NavIdent) =
        ActiveConversationRepo.connect(conversationId, owner)

    override suspend fun renew(connectionId: UUID) = ActiveConversationRepo.renew(connectionId)

    override suspend fun disconnect(connectionId: UUID) = ActiveConversationRepo.disconnect(connectionId)

    override suspend fun getActiveConversations(owner: NavIdent, request: ActiveConversationPageRequest) =
        ActiveConversationRepo.getActiveConversations(owner, request)

    override suspend fun getStatistics() = ActiveConversationStatisticsRepo.getStatistics()

    override suspend fun deleteExpiredConnections() = ActiveConversationRepo.deleteExpiredConnections()
}
