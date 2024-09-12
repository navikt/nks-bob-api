package no.nav.nks_ai.citation

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.message.MessageDAO
import no.nav.nks_ai.message.Messages
import no.nav.nks_ai.now
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

object Citations : UUIDTable() {
    val message = reference("message", Messages)
    val text = text("text", eagerLoading = true)
    val article = varchar("article", 255)
    val title = varchar("title", 255)
    val section = varchar("section", 255)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
}

class CitationDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CitationDAO>(Citations)

    var message by MessageDAO referencedOn Citations.message
    var text by Citations.text
    var article by Citations.article
    var title by Citations.title
    var section by Citations.section
    var createdAt by Citations.createdAt
}

fun CitationDAO.toModel() = Citation(
    id = id.toString(),
    text = text,
    article = article,
    title = title,
    section = section,
    createdAt = createdAt
)

@Serializable
data class Citation(
    val id: String,
    val text: String,
    val article: String,
    val title: String,
    val section: String,
    val createdAt: LocalDateTime,
)

@Serializable
data class NewCitation(
    val text: String,
    val article: String,
    val title: String,
    val section: String,
)

class CitationRepo() {
    suspend fun addCitation(
        messageId: UUID,
        citation: NewCitation,
    ): Citation? =
        suspendTransaction {
            val message = MessageDAO.findById(messageId)
                ?: return@suspendTransaction null // TODO error

            CitationDAO.new {
                this.text = citation.text
                this.article = citation.article
                this.title = citation.title
                this.section = citation.section
                this.message = message
            }.toModel()
        }

    suspend fun addCitations(
        messageId: UUID,
        citations: List<NewCitation>
    ): List<Citation> =
        suspendTransaction {
            Citations.batchInsert(citations) { row ->
                this[Citations.text] = row.text
                this[Citations.article] = row.article
                this[Citations.title] = row.title
                this[Citations.section] = row.section
                this[Citations.message] = messageId
            }.map { row ->
                Citation(
                    id = row[Citations.id].toString(),
                    text = row[Citations.text],
                    article = row[Citations.article],
                    title = row[Citations.title],
                    section = row[Citations.section],
                    createdAt = row[Citations.createdAt]
                )
            }
        }

    suspend fun getCitation(id: UUID): Citation? =
        suspendTransaction {
            CitationDAO.findById(id)?.toModel()
        }

    suspend fun getCitations(ids: List<UUID>): List<Citation> =
        suspendTransaction {
            CitationDAO.find { Citations.id inList ids }
                .map { it.toModel() }
        }
}