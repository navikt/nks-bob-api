package no.nav.nks_ai.core.ignoredWords

import arrow.core.raise.either
import no.nav.nks_ai.app.*
import no.nav.nks_ai.core.conversation.ConversationDAO
import no.nav.nks_ai.core.conversation.ConversationId
import no.nav.nks_ai.core.conversation.Conversations
import no.nav.nks_ai.core.conversation.toConversationId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.select
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
    id = id.value.toIgnoredWordId(),
    conversationId = conversation?.id?.value?.toConversationId(),
    value = value,
    validationType = validationType,
)

object IgnoredWordRepo {
    suspend fun getIgnoredWord(id: IgnoredWordId): ApplicationResult<IgnoredWord> =
        suspendTransaction {
            either {
                IgnoredWordsDAO.findById(id.value)?.toModel() ?: raise(ApplicationError.IgnoredWordNotFound(id))
            }
        }

    suspend fun getAllIgnoredWords(pagination: Pagination): ApplicationResult<Page<IgnoredWord>> =
        suspendTransaction {
            either {
                Page(
                    data = IgnoredWordsDAO.all()
                        .paginated(pagination, IgnoredWords)
                        .map(IgnoredWordsDAO::toModel),
                    total = IgnoredWordsDAO.all().count()
                )
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

    suspend fun deleteIgnoredWord(id: IgnoredWordId): ApplicationResult<Unit> = suspendTransaction {
        either {
            IgnoredWordsDAO.findById(id.value)?.delete() ?: raise(ApplicationError.IgnoredWordNotFound(id))
        }
    }

    suspend fun getIgnoredWordsAggregations(pagination: Pagination): ApplicationResult<Page<IgnoredWordAggregation>> =
        suspendTransaction {
            either {
                val countAlias = IgnoredWords.value.count().alias("count")
                val query = IgnoredWords.select(
                    IgnoredWords.value,
                    IgnoredWords.validationType,
                    countAlias
                ).groupBy(IgnoredWords.value, IgnoredWords.validationType)

                val total = query.count()
                val data = query
                    .orderBy(countAlias to SortOrder.DESC)
                    .limit(pagination.size)
                    .offset((pagination.size * pagination.page).toLong())
                    .map { row ->
                        IgnoredWordAggregation(
                            row[IgnoredWords.value],
                            row[IgnoredWords.validationType],
                            row[countAlias].toInt()
                        )
                    }

                Page(
                    data = data,
                    total = total,
                )
            }
        }
}

