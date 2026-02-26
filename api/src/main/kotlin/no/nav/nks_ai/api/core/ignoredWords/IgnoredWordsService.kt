package no.nav.nks_ai.api.core.ignoredWords

import arrow.core.raise.either
import arrow.core.raise.ensure
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.app.Pagination
import no.nav.nks_ai.api.core.conversation.ConversationRepo
import no.nav.nks_ai.api.core.user.NavIdent

interface IgnoredWordsService {
    suspend fun getIgnoredWord(id: IgnoredWordId): ApplicationResult<IgnoredWord>

    suspend fun getAllIgnoredWords(pagination: Pagination): ApplicationResult<Page<IgnoredWord>>

    suspend fun getAllIgnoredWordsAggregated(pagination: Pagination): ApplicationResult<Page<IgnoredWordAggregation>>

    suspend fun addIgnoredWord(
        navIdent: NavIdent,
        newIgnoredWord: NewIgnoredWord,
    ): ApplicationResult<IgnoredWord>

    suspend fun deleteIgnoredWord(id: IgnoredWordId): ApplicationResult<Unit>

}

fun ignoredWordsService() = object : IgnoredWordsService {
    override suspend fun getIgnoredWord(id: IgnoredWordId): ApplicationResult<IgnoredWord> =
        IgnoredWordRepo.getIgnoredWord(id)

    override suspend fun getAllIgnoredWords(pagination: Pagination): ApplicationResult<Page<IgnoredWord>> =
        IgnoredWordRepo.getAllIgnoredWords(pagination)

    override suspend fun getAllIgnoredWordsAggregated(pagination: Pagination): ApplicationResult<Page<IgnoredWordAggregation>> =
        IgnoredWordRepo.getIgnoredWordsAggregations(pagination)

    override suspend fun addIgnoredWord(
        navIdent: NavIdent,
        newIgnoredWord: NewIgnoredWord
    ): ApplicationResult<IgnoredWord> = either {
        newIgnoredWord.conversationId?.let { conversationId ->
            ensure(
                ConversationRepo.getConversation(conversationId, navIdent).isRight()
            ) { ApplicationError.ConversationNotFound(conversationId) }
        }
        IgnoredWordRepo.addIgnoredWord(
            conversationId = newIgnoredWord.conversationId,
            value = newIgnoredWord.value,
            validationType = newIgnoredWord.validationType
        ).bind()
    }

    override suspend fun deleteIgnoredWord(id: IgnoredWordId): ApplicationResult<Unit> {
        return IgnoredWordRepo.deleteIgnoredWord(id)
    }

}

