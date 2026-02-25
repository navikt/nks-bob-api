package no.nav.nks_ai.api.core.conversation

import arrow.core.raise.either
import arrow.core.right
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.BaseEntity
import no.nav.nks_ai.api.app.BaseEntityClass
import no.nav.nks_ai.api.app.BaseTable
import no.nav.nks_ai.api.app.bcryptVerified
import no.nav.nks_ai.api.app.suspendTransaction
import no.nav.nks_ai.api.app.truncate
import no.nav.nks_ai.api.core.message.Messages
import no.nav.nks_ai.api.core.user.NavIdent
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.*

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

    suspend fun getEmptyConversationsCreatedBefore(
        dateTime: LocalDateTime,
    ): ApplicationResult<List<Conversation>> =
        suspendTransaction {
            val query = Conversations.selectAll().where {
                (Conversations.createdAt less dateTime) and notExists(
                    Messages.select(Messages.conversation)
                        .where { Messages.conversation eq Conversations.id }
                )
            }

            ConversationDAO.wrapRows(query).toList().map { it.toModel() }.right()
        }
}