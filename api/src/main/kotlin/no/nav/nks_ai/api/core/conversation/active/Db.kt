package no.nav.nks_ai.api.core.conversation.active

import no.nav.nks_ai.api.app.BaseTable
import no.nav.nks_ai.api.core.conversation.Conversations
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

internal object ActiveConversationConnections : BaseTable("active_conversation_connections") {
    val conversation = reference("conversation_id", Conversations, onDelete = ReferenceOption.CASCADE)
    val owner = varchar("owner", 255)
}
