package no.nav.nks_ai.core.article

import com.google.cloud.bigquery.QueryParameterValue
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.bq.BigQueryClient
import org.intellij.lang.annotations.Language

class ArticleService(
    private val bigQueryClient: BigQueryClient,
) {
    fun getArticle(articleId: String): List<Any> {
        val dataset = Config.bigQuery.kunnskapsbaseDataset
        val tableName = Config.bigQuery.kunnskapsartiklerTable

        @Language("BigQuery")
        val queryString = """
               SELECT
                  `ArticleType`,
                  `KnowledgeArticleId`,
                  `Title`,
                  `UrlName`
                FROM
                  `$dataset.$tableName`
                WHERE
                  (`ArticleType` IN ('Artikkel'))
                  AND (`KnowledgeArticleId` IN (@articleId));
           """.trimIndent()

        val queryResult = bigQueryClient.query(
            queryString,
            mapOf("articleId" to QueryParameterValue.string(articleId)),
        ) { result ->
            result.iterateAll().flatMap { it.map { it.value } }.filterNotNull()
        }

        return queryResult.fold(
            { emptyList() },
            { it },
        )
    }
}