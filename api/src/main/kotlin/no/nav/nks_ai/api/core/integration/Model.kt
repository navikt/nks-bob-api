package no.nav.nks_ai.api.core.integration

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.user.NavIdent
import arrow.core.raise.either

@Serializable
data class AcceptedIntegrationMessage(
    val conversationId: ConversationId,
    val messageId: MessageId,
)

@Serializable
data class IntegrationProblem(
    val title: String,
    val status: Int,
    val detail: String,
    val type: String = "about:blank",
    val conversationId: ConversationId? = null,
    val messageId: MessageId? = null,
)

/**
 * Henter og validerer `{navIdent}`-stien i [IntegrationConversationService.ROOT]. Selve
 * formatvalideringen (NAVident-mønster) gjøres av tjenestelaget (`validateOwner`), slik at
 * feilmeldingen blir konsistent uansett hvor kallet kommer fra.
 */
fun ApplicationCall.pathNavIdent(name: String = "navIdent"): ApplicationResult<NavIdent> = either {
    val raw = parameters[name]
    if (raw.isNullOrBlank()) {
        raise(ApplicationError.BadRequest("Missing navIdent path parameter"))
    }
    NavIdent(raw)
}
