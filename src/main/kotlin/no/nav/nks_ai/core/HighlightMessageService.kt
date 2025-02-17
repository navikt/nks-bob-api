package no.nav.nks_ai.core

import com.google.cloud.bigquery.InsertAllRequest.RowToInsert
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.bq.BigQueryClient
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService

class HighlightMessageService(
    private val bigQueryClient: BigQueryClient,
    private val messageService: MessageService,
) {
    suspend fun highlightMessage(messageId: MessageId) {
        val message = messageService.getMessage(messageId)
        if (message == null) {
            // TODO handle error
            return
        }

        bigQueryClient.insert(
            dataset = Config.bigQuery.testgrunnlagDataset,
            table = Config.bigQuery.fremhevedeSporsmalTable,
            row = RowToInsert.of(message.toRowMap())
        )
    }
}

private fun Message.toRowMap(): Map<String, Any> =
    mapOf(
        "message_id" to id.value.toString(),
        "user_question" to (userQuestion ?: ""),
        "contextualized_question" to (contextualizedQuestion ?: ""),
        "answer_content" to content,
        "context" to Json.encodeToString(context),
        "citations" to citations,
    )