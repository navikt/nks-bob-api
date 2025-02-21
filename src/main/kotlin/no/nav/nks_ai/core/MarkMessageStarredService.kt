package no.nav.nks_ai.core

import arrow.core.Either
import arrow.core.raise.either
import com.google.cloud.bigquery.InsertAllRequest.RowToInsert
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.DomainError
import no.nav.nks_ai.app.bq.BigQueryClient
import no.nav.nks_ai.app.now
import no.nav.nks_ai.core.message.Citation
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService

class MarkMessageStarredService(
    private val bigQueryClient: BigQueryClient,
    private val messageService: MessageService,
) {
    suspend fun markStarred(messageId: MessageId): Either<ApplicationError, Message> = either {
        val message = messageService.getMessage(messageId)
            ?: raise(DomainError.MessageNotFound(messageId))

        bigQueryClient.insert(
            dataset = Config.bigQuery.testgrunnlagDataset,
            table = Config.bigQuery.stjernemarkerteSvarTable,
            row = RowToInsert.of(message.toRowMap())
        ).mapLeft { it.toApplicationError() }.bind()

        messageService.starMessage(messageId)
            ?: raise(DomainError.MessageNotFound(messageId))
    }
}

private fun Message.toRowMap(): Map<String, Any?> =
    mapOf(
        "user_question" to userQuestion,
        "contextualized_question" to contextualizedQuestion,
        "answer_content" to content,
        "context" to Json.encodeToString(context),
        "citations" to citations.map(Citation::toRowMap),
        "created_at" to LocalDateTime.now().toString()
    )

private fun Citation.toRowMap(): Map<String, Any> =
    mapOf("text" to text, "source_id" to sourceId)
