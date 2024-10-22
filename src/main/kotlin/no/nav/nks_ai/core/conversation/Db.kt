package no.nav.nks_ai.core.conversation

import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
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
    conversationId: UUID,
    navIdent: String
): ConversationDAO? =
    find {
        Conversations.id eq conversationId and (Conversations.owner eq navIdent)
    }.firstOrNull()

private fun ConversationDAO.Companion.findAllByNavIdent(
    navIdent: String
): SizedIterable<ConversationDAO> = find { Conversations.owner eq navIdent }

private fun ConversationDAO.toModel() = Conversation(
    id = id.toString(),
    title = title,
    createdAt = createdAt,
    owner = owner,
)

object ConversationRepo {
    suspend fun addConversation(navIdent: String, conversation: NewConversation): Conversation =
        suspendTransaction {
            ConversationDAO.new {
                title = conversation.title
                owner = navIdent
            }.toModel()
        }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String): Unit =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)?.delete()
        }

    suspend fun deleteAllConversations(navIdent: String): Unit =
        suspendTransaction {
            ConversationDAO.findAllByNavIdent(navIdent).forEach { it.delete() }
        }

    suspend fun getConversation(conversationId: UUID, navIdent: String): Conversation? =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)
                ?.toModel()
        }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        suspendTransaction {
            ConversationDAO.find { Conversations.owner eq navIdent }
                .map { it.toModel() }
        }

    suspend fun updateConversation(id: UUID, navIdent: String, conversation: UpdateConversation): Conversation? =
        suspendTransaction {
            ConversationDAO
                .findByIdAndNavIdent(id, navIdent)
                ?.apply {
                    title = conversation.title
                }?.toModel()
        }
}