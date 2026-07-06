package no.nav.nks_ai.testutil

import arrow.core.Either
import arrow.core.right
import com.google.cloud.bigquery.InsertAllRequest.RowToInsert
import com.google.cloud.bigquery.QueryParameterValue
import com.google.cloud.bigquery.TableResult
import no.nav.nks_ai.api.app.bq.BigQueryClient
import no.nav.nks_ai.api.app.bq.BigQueryError

/**
 * Fake BigQueryClient for integrasjonstester.
 *
 * Lagrer alle insert-kall i minnet slik at tester kan verifisere
 * at riktige rader ble sendt. Returnerer alltid suksess.
 */
class FakeBigQueryClient : BigQueryClient {
    data class InsertCall(val dataset: String, val table: String, val row: RowToInsert)

    val insertCalls = mutableListOf<InsertCall>()

    override fun insert(
        dataset: String,
        table: String,
        row: RowToInsert,
    ): Either<BigQueryError, RowToInsert> {
        insertCalls.add(InsertCall(dataset, table, row))
        return row.right()
    }

    override fun <T> query(
        query: String,
        parameters: Map<String, QueryParameterValue>,
        block: (result: TableResult) -> T,
    ): Either<BigQueryError, T> {
        throw UnsupportedOperationException("FakeBigQueryClient støtter ikke query — legg til stub ved behov")
    }
}
