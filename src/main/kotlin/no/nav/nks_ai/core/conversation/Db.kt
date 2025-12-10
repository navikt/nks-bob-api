package no.nav.nks_ai.core.conversation

import arrow.core.raise.either
import arrow.core.right
import java.util.*
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
import no.nav.nks_ai.app.BaseEntity
import no.nav.nks_ai.app.BaseEntityClass
import no.nav.nks_ai.app.BaseTable
import no.nav.nks_ai.app.bcryptVerified
import no.nav.nks_ai.app.suspendTransaction
import no.nav.nks_ai.app.truncate
import no.nav.nks_ai.core.user.NavIdent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

internal object Conversations : BaseTable("conversations") {
    val title = varchar("title", 255)
    val owner = varchar("owner", 255)
}

internal class ConversationDAO(id: EntityID<UUID>) : BaseEntity(id, Conversations) {
    companion object : BaseEntityClass<ConversationDAO>(Conversations)

    var title by Conversations.title
    var owner by Conversations.owner
}

private fun ConversationDAO.Companion.findByIdAndNavIdent(
    conversationId: ConversationId,
    navIdent: NavIdent,
): ConversationDAO? =
    find {
        Conversations.id eq conversationId.value and (Conversations.owner bcryptVerified navIdent)
    }.firstOrNull()

private fun ConversationDAO.Companion.findAllByNavIdent(
    navIdent: NavIdent,
): SizedIterable<ConversationDAO> =
    find { Conversations.owner bcryptVerified navIdent }

private fun ConversationDAO.toModel() = Conversation(
    id = id.value.toConversationId(),
    title = title,
    createdAt = createdAt,
)

object ConversationRepo {
    suspend fun addConversation(navIdent: NavIdent, conversation: NewConversation): ApplicationResult<Conversation> =
        suspendTransaction {
            either {
                ConversationDAO.new {
                    title = conversation.title.truncate(255)
                    owner = navIdent.hash
                }.toModel()
            }
        }

    suspend fun deleteConversation(conversationId: ConversationId, navIdent: NavIdent): ApplicationResult<Unit> =
        suspendTransaction {
            either {
                ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)?.delete()
                    ?: raise(ApplicationError.ConversationNotFound(conversationId))
            }
        }

    suspend fun deleteAllConversations(navIdent: NavIdent): ApplicationResult<Unit> =
        suspendTransaction {
            ConversationDAO.findAllByNavIdent(navIdent).forEach { it.delete() }.right()
        }

    suspend fun getConversation(conversationId: ConversationId, navIdent: NavIdent): ApplicationResult<Conversation> =
        suspendTransaction {
            either {
                ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)
                    ?.toModel()
                    ?: raise(ApplicationError.ConversationNotFound(conversationId))
            }
        }

    /**
     * Warning: Use with caution, intended for the admin API. Will potentially expose a conversation from another user.
     */
    suspend fun getConversation(conversationId: ConversationId): ApplicationResult<Conversation> =
        suspendTransaction {
            either {
                ConversationDAO.findById(conversationId.value)
                    ?.toModel()
                    ?: raise(ApplicationError.ConversationNotFound(conversationId))
            }
        }

    suspend fun getAllConversations(navIdent: NavIdent): ApplicationResult<List<Conversation>> =
        suspendTransaction {
            ConversationDAO.findAllByNavIdent(navIdent)
                .map { it.toModel() }
                .right()
        }

    suspend fun updateConversation(
        id: ConversationId,
        navIdent: NavIdent,
        conversation: UpdateConversation
    ): ApplicationResult<Conversation> =
        suspendTransaction {
            either {
                ConversationDAO
                    .findByIdAndNavIdent(id, navIdent)
                    ?.apply {
                        title = conversation.title
                    }?.toModel()
                    ?: raise(ApplicationError.ConversationNotFound(id))
            }
        }

    suspend fun deleteConversations(
        conversationIds: List<ConversationId>,
    ): ApplicationResult<Int> =
        suspendTransaction {
            Conversations.deleteWhere {
                Conversations.id inList conversationIds.map { it.value }
            }.right()
        }

    suspend fun getConversationsCreatedBefore(
        dateTime: LocalDateTime,
    ): ApplicationResult<List<Conversation>> =
        suspendTransaction {
            ConversationDAO.find {
                Conversations.createdAt.less(dateTime)
            }.map { it.toModel() }.right()
        }
}