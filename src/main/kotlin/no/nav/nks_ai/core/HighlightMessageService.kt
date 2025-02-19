package no.nav.nks_ai.core

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.cloud.bigquery.InsertAllRequest.RowToInsert
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.bq.BigQueryClient
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService

class HighlightMessageService(
    private val bigQueryClient: BigQueryClient,
    private val messageService: MessageService,
) {
    suspend fun highlightMessage(messageId: MessageId): Either<ApplicationError, Message> {
        val message = messageService.getMessage(messageId)
            ?: return messageNotFound.left()

        return bigQueryClient.insert(
            dataset = Config.bigQuery.testgrunnlagDataset,
            table = Config.bigQuery.fremhevedeSporsmalTable,
            row = RowToInsert.of(message.toRowMap())
        ).fold(
            { it.toApplicationError().left() },
            {
                messageService.starMessage(messageId)?.right()
                    ?: messageNotFound.left()
            }
        )
    }
}

private val messageNotFound = ApplicationError(
    code = HttpStatusCode.NotFound,
    message = "",
    description = "",
)

private fun Message.toRowMap(): Map<String, Any> =
    mapOf(
        "message_id" to id.value.toString(),
        "user_question" to (userQuestion ?: ""),
        "contextualized_question" to (contextualizedQuestion ?: ""),
        "answer_content" to content,
        "context" to Json.encodeToString(context),
        "citations" to citations,
    )