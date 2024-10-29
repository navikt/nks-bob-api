package no.nav.nks_ai.core.conversation

import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
import no.nav.nks_ai.core.user.NavIdent
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

internal object Conversations : UUIDTable() {
    val title = varchar("title", 255)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val owner = varchar("owner", 255)
}

internal class ConversationDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ConversationDAO>(Conversations)

    var title by Conversations.title
    var createdAt by Conversations.createdAt
    var owner by Conversations.owner
}

private fun ConversationDAO.Companion.findByIdAndNavIdent(
    conversationId: ConversationId,
    navIdent: NavIdent,
): ConversationDAO? =
    find {
        Conversations.id eq conversationId.value and (Conversations.owner eq navIdent.value)
    }.firstOrNull()

private fun ConversationDAO.Companion.findAllByNavIdent(
    navIdent: NavIdent,
): SizedIterable<ConversationDAO> = find { Conversations.owner eq navIdent.value }

private fun ConversationDAO.toModel() = Conversation(
    id = id.value.toConversationId(),
    title = title,
    createdAt = createdAt,
    owner = owner,
)

object ConversationRepo {
    suspend fun addConversation(navIdent: NavIdent, conversation: NewConversation): Conversation =
        suspendTransaction {
            ConversationDAO.new {
                title = conversation.title
                owner = navIdent.value
            }.toModel()
        }

    suspend fun deleteConversation(conversationId: ConversationId, navIdent: NavIdent): Unit =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)?.delete()
        }

    suspend fun deleteAllConversations(navIdent: NavIdent): Unit =
        suspendTransaction {
            ConversationDAO.findAllByNavIdent(navIdent).forEach { it.delete() }
        }

    suspend fun getConversation(conversationId: ConversationId, navIdent: NavIdent): Conversation? =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)
                ?.toModel()
        }

    suspend fun getAllConversations(navIdent: NavIdent): List<Conversation> =
        suspendTransaction {
            ConversationDAO.findAllByNavIdent(navIdent)
                .map { it.toModel() }
        }

    suspend fun updateConversation(
        id: ConversationId,
        navIdent: NavIdent,
        conversation: UpdateConversation
    ): Conversation? =
        suspendTransaction {
            ConversationDAO
                .findByIdAndNavIdent(id, navIdent)
                ?.apply {
                    title = conversation.title
                }?.toModel()
        }

    suspend fun deleteConversations(
        conversationIds: List<ConversationId>,
    ): Unit =
        suspendTransaction {
            ConversationDAO.find {
                Conversations.id inList conversationIds.map { it.value }
            }.forEach { it.delete() }
        }

    suspend fun getConversationsCreatedBefore(
        dateTime: LocalDateTime,
    ): List<Conversation> =
        suspendTransaction {
            ConversationDAO.find {
                Conversations.createdAt.less(dateTime)
            }.map { it.toModel() }
        }
}