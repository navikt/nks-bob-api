package no.nav.nks_ai.kbs

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import no.nav.nks_ai.core.message.Context
import no.nav.nks_ai.core.message.Message
import no.nav.nks_ai.core.message.MessageRole
import no.nav.nks_ai.core.message.NewCitation

@Serializable
data class KbsChatRequest(
    val question: String,
    val history: List<KbsChatMessage>,
)

@Serializable
enum class KbsMessageRole {
    @SerialName("human")
    Human,

    @SerialName("ai")
    AI,
}

fun KbsMessageRole.Companion.fromMessageRole(messageRole: MessageRole): KbsMessageRole =
    when (messageRole) {
        MessageRole.Human -> KbsMessageRole.Human
        MessageRole.AI -> KbsMessageRole.AI
    }

fun KbsChatMessage.Companion.fromMessage(message: Message): KbsChatMessage = KbsChatMessage(
    content = message.content,
    role = KbsMessageRole.fromMessageRole(message.messageRole)
)

@Serializable
data class KbsChatMessage(
    val content: String,
    val role: KbsMessageRole,
)

@Serializable
data class KbsChatResponse(
    val answer: KbsChatAnswer,
    val context: List<KbsChatContext>,
    @SerialName("follow_up") val followUp: List<String> = emptyList(),
    val question: KbsChatQuestion,
)

@Serializable
data class KbsChatAnswer(
    val text: String,
    val citations: List<KbsCitation>
)

@Serializable
data class KbsChatQuestion(
    val user: String,
    val contextualized: String,
)

@Serializable
data class KbsCitation(
    val text: String,
    @SerialName("source_id") val sourceId: Int,
)

fun KbsCitation.toNewCitation() =
    NewCitation(
        text = text,
        sourceId = sourceId,
    )

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

fun KbsChatContext.toModel(): Context =
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
    )

@Serializable
sealed class KbsErrorResponse {
    abstract val title: String
    abstract val detail: String

    @OptIn(ExperimentalSerializationApi::class)
    @JsonClassDiscriminator("type")
    @Serializable
    sealed class KbsTypedError : KbsErrorResponse() {
        abstract val type: KbsErrorType
        abstract val status: Int

        @Serializable
        @SerialName(VALIDATION_ERROR_NAME)
        data class KbsValidationError(
            override val type: KbsErrorType = KbsErrorType.ValidationError,
            override val status: Int,
            override val title: String,
            override val detail: String,
        ) : KbsTypedError()

        @Serializable
        @SerialName(MODEL_ERROR_NAME)
        data class KbsModelError(
            override val type: KbsErrorType = KbsErrorType.ModelError,
            override val status: Int,
            override val title: String,
            override val detail: String,
        ) : KbsTypedError()
    }

    @Serializable
    data class KbsGenericError(
        override val detail: String
    ) : KbsErrorResponse() {
        override val title: String
            get() = "Unknown error"
    }
}

private const val VALIDATION_ERROR_NAME = "urn:nks-kbs:error:validation"
private const val MODEL_ERROR_NAME = "urn:nks-kbs:error:model"

@Serializable
enum class KbsErrorType {
    @SerialName(VALIDATION_ERROR_NAME)
    ValidationError,

    @SerialName(MODEL_ERROR_NAME)
    ModelError,
}

internal data class KbsValidationException(
    val status: Int,
    val title: String,
    val detail: String,
) : Throwable() {
    fun toError() =
        KbsErrorResponse.KbsTypedError.KbsValidationError(
            status = status,
            title = title,
            detail = detail,
        )

    companion object {
        fun fromError(error: KbsErrorResponse.KbsTypedError.KbsValidationError) =
            KbsValidationException(
                status = error.status,
                title = error.title,
                detail = error.detail,
            )
    }
}
