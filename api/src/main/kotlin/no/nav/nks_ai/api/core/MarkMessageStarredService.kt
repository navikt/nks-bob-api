package no.nav.nks_ai.api.core

import arrow.core.raise.either
import com.google.cloud.bigquery.InsertAllRequest.RowToInsert
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Config
import no.nav.nks_ai.api.app.bq.BigQueryClient
import no.nav.nks_ai.api.app.getConfig
import no.nav.nks_ai.api.app.now
import no.nav.nks_ai.api.core.message.Citation
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageService

class MarkMessageStarredService(
    private val bigQueryClient: BigQueryClient,
    private val messageService: MessageService,
) {
    suspend fun markStarred(messageId: MessageId): ApplicationResult<Message> = either {
        val config = getConfig()
        val message = messageService.getMessage(messageId).bind()

        bigQueryClient.insert(
            dataset = config.bigQuery.testgrunnlagDataset,
            table = config.bigQuery.stjernemarkerteSvarTable,
            row = RowToInsert.of(message.toRowMap())
        ).mapLeft { it.toApplicationError() }.bind()

        messageService.markStarredMessageUploaded(messageId).bind()
    }
}

private fun Message.toRowMap(): Map<String, Any?> =
    mapOf(
        "user_question" to userQuestion,
        "contextualized_question" to contextualizedQuestion,
        "answer_content" to content,
        "context" to Json.encodeToString(context),
        "citations" to citations.map(Citation::toRowMap),
        "created_at" to LocalDateTime.Companion.now().toString()
    )

private fun Citation.toRowMap(): Map<String, Any> =
    mapOf("text" to text, "source_id" to sourceId)
