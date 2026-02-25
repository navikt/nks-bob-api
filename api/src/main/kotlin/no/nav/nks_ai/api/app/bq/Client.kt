package no.nav.nks_ai.api.app.bq

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.google.cloud.NoCredentials
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
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.Config

private val logger = KotlinLogging.logger {}

class BigQueryClient {
    private val project = Config.bigQuery.projectId
    private val bigQuery: BigQuery =
        if (Config.nais.isRunningOnNais) {
            BigQueryOptions
                .newBuilder()
                .setProjectId(project)
                .build()
                .service
        } else {
            // separate config for local dev.
            BigQueryOptions
                .newBuilder()
                .setProjectId(project)
                .setHost("http://localhost:9050")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .service
        }

    fun insert(
        dataset: String,
        table: String,
        row: RowToInsert
    ): Either<BigQueryError, RowToInsert> = either {
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
                raise(
                    BigQueryError(
                        "Error response from BigQuery",
                        errors = response.insertErrors.entries.map { "${it.value}" },
                    )
                )
            }

            row
        } catch (e: BigQueryException) {
            logger.error(e) { "Error when inserting to BigQuery" }
            raise(
                BigQueryError(
                    e.message ?: "Error when inserting to BigQuery",
                    e,
                    e.errors?.map { it.message } ?: emptyList(),
                )
            )
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
                .setDryRun(false)
                .setUseQueryCache(true)
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
) {
    fun toApplicationError() = ApplicationError.InternalServerError(
        message = "BigQuery Error",
        description = message,
    )
}