package no.nav.nks_ai.app.bq

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.InsertAllRequest
import com.google.cloud.bigquery.InsertAllRequest.RowToInsert
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.QueryParameterValue
import com.google.cloud.bigquery.TableId
import com.google.cloud.bigquery.TableResult
import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.nks_ai.app.Config

private val logger = KotlinLogging.logger {}

class BigQueryClient {
    private val project = Config.bigQuery.projectId
    private val bigQuery: BigQuery =
        BigQueryOptions
            .newBuilder()
            .setProjectId(project)
            .build()
            .service

    fun insert(
        dataset: String,
        table: String,
        row: RowToInsert
    ): Either<BigQueryError, RowToInsert> {
        try {
            val tableId = TableId.of(project, dataset, table)
            val response = bigQuery.insertAll(
                InsertAllRequest.newBuilder(tableId)
                    .addRow(row)
                    .build()
            )

            if (response.hasErrors()) {
                response.insertErrors.entries.map {
                    logger.error { "Error response from BigQuery ${it.key} ${it.value}" }
                }
            }

            return row.right()
        } catch (e: BigQueryException) {
            logger.error(e) { "Error when inserting to BigQuery" }
            return BigQueryError(
                e.message ?: "Error when inserting to BigQuery",
                e,
                e.errors.map { it.message },
            ).left()
        }
    }

    fun <T> query(
        query: String,
        parameters: Map<String, QueryParameterValue> = emptyMap(),
        block: (result: TableResult) -> T,
    ): Either<BigQueryError, T> {
        try {
            val queryConfig = QueryJobConfiguration.newBuilder(query)
                .setNamedParameters(parameters)
                .build()

            return block(bigQuery.query(queryConfig)).right()
        } catch (e: BigQueryException) {
            logger.error(e) { "Error when fetching from BigQuery" }
            return BigQueryError(
                e.message ?: "Error when fetching from BigQuery",
                e,
                e.errors.map { it.message },
            ).left()
        } catch (e: InterruptedException) {
            logger.error(e) { "InterruptedException when fetching from BigQuery" }
            return BigQueryError(
                e.message ?: "InterruptedException when fetching from BigQuery",
                e
            ).left()
        }
    }
}

data class BigQueryError(
    val message: String,
    val cause: Throwable? = null,
    val errors: List<String> = emptyList(),
)