package no.nav.nks_ai.api.v2.kbs

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.core.message.Context

@Serializable
sealed class KbsStreamResponse {
    @Serializable
    data class KbsChatResponse(
        val answer: String,
        val context: Map<String, KbsChatContext>,
        val citations: Map<String, List<String>>,
        @SerialName("follow_up") val followUp: List<String> = emptyList(),
        val tools: List<KbsToolCall>,
        val thinking: List<String>,
    ) : KbsStreamResponse()

    @Serializable
    data class KbsTokenChunkResponse(
        val chunk: String
    ) : KbsStreamResponse()

    @Serializable
    data class StatusUpdateResponse(
        val text: String
    ) : KbsStreamResponse()
}

@Serializable
data class KbsChatContext(
    val content: String,
    val title: String,
    val ingress: String,
    val source: String,
    val url: String,
    val anchor: String?,
    @SerialName("article_id") val articleId: String,
    @SerialName("article_column") val articleColumn: String?,
    @SerialName("last_modified") val lastModified: LocalDateTime?,
    @SerialName("semantic_similarity") val semanticSimilarity: Double,
)

fun KbsChatContext.toModel(sourceId: String): Context =
    Context(
        content = content,
        title = title,
        ingress = ingress,
        source = source,
        url = url,
        anchor = anchor,
        articleId = articleId,
        articleColumn = articleColumn,
        lastModified = lastModified,
        semanticSimilarity = semanticSimilarity,
        sourceId = sourceId,
    )

@Serializable
data class KbsToolCall(
    val name: String,
    val arguments: Map<String, String>,
    val success: Boolean,
)