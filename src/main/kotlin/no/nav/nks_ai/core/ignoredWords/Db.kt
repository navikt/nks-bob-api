package no.nav.nks_ai.core.ignoredWords

import arrow.core.raise.either
import no.nav.nks_ai.app.*
import no.nav.nks_ai.core.conversation.ConversationDAO
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.Conversations
import no.nav.nks_ai.core.conversation.toConversationId
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.selectAll
import java.util.*

internal object IgnoredWords : BaseTable("ignored_words") {
    val conversation = reference("conversation", Conversations).nullable()
    val value = text("value", eagerLoading = true)
    val validationType = text("validation_type", eagerLoading = true)
}

internal class IgnoredWordsDAO(id: EntityID<UUID>) : BaseEntity(id, IgnoredWords) {
    companion object : BaseEntityClass<IgnoredWordsDAO>(IgnoredWords)

    var conversation by ConversationDAO.Companion optionalReferencedOn IgnoredWords.conversation
    var value by IgnoredWords.value
    var validationType by IgnoredWords.validationType
}

internal fun IgnoredWordsDAO.toModel() = IgnoredWord(
    id = id.value.toIgnoredWordsId(),
    conversationId = conversation?.id?.value?.toConversationId(),
    value = value,
    validationType = validationType,
)

object IgnoredWordRepo {
    suspend fun getIgnoredWord(id: IgnoredWordsId): ApplicationResult<IgnoredWord> =
        suspendTransaction {
            either {
                IgnoredWordsDAO.findById(id.value)?.toModel() ?: raise(ApplicationError.IgnoredWordNotFound(id))
            }
        }

    suspend fun addIgnoredWord(
        value: String,
        validationType: String,
        conversationId: ConversationId?
    ): ApplicationResult<IgnoredWord> =
        suspendTransaction {
            either {
                val conversation = conversationId?.let {
                    ConversationDAO.findById(conversationId.value) ?: raise(
                        ApplicationError.ConversationNotFound(
                            conversationId
                        )
                    )
                }
                IgnoredWordsDAO.new {
                    this.value = value
                    this.validationType = validationType
                    this.conversation = conversation
                }.toModel()
            }
        }
    suspend fun deleteIgnoredWord(id: IgnoredWordsId): ApplicationResult<Unit> = suspendTransaction {
        either {
            IgnoredWordsDAO.findById(id.value)?.delete() ?: raise(ApplicationError.IgnoredWordNotFound(id))
        }
    }

    suspend fun getIgnoredWordsAggregations(): ApplicationResult<List<IgnoredWordAggregation>> = suspendTransaction {
        either {
            IgnoredWords.selectAll().groupBy(IgnoredWords.value).map { row ->
                row.
                IgnoredWordAggregation(value, words.size)
            }
            IgnoredWordsDAO.all().map { it.toModel() }.groupBy { it.value }.map { (value, words) ->
                IgnoredWordAggregation(value, words.size)
            }
        }
    }
}

