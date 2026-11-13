package no.nav.nks_ai.api.core.integration

import arrow.core.flatten
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.right
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.user.NavIdent

private val logger = KotlinLogging.logger { }

/**
 * Ruter for eksterne partnere (f.eks. Salesforce) til å opprette samtaler og meldinger på vegne
 * av en veileder, se [IntegrationConversationService]. Beskyttet med `authenticate("Maskinporten")`
 * i Application.kt.
 *
 * Feil returneres som [IntegrationProblem] (RFC 7807-stil) i stedet for det interne
 * `ErrorResponse`-formatet som brukes ellers i appen, slik at eksterne konsumenter får en stabil,
 * dokumentert feilkontrakt uavhengig av interne feiltyper.
 *
 * 🔴 Rød sone: se README og [IntegrationConversationService] for eierskap, atomisk lagring og
 * avbrutt bakgrunnsbehandling. Ikke gi Salesforce reell tilgang (nais accessPolicy/Maskinporten-
 * consumer) før miljøoppsett er avklart.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.integrationConversationRoutes(
    service: IntegrationConversationService,
) {
    route(IntegrationConversationService.ROOT) {
        post {
            call.respondIntegrationEither(HttpStatusCode.Created) {
                val request = call.receive<IntegrationConversationRequest>()
                val newConversation = NewConversation(request.title, request.initialMessage)
                service.createConversation(NavIdent(request.navIdent), newConversation).bind().right()
            }
        }.describe {
            description = "Create a new conversation on behalf of a NAV-ident (integration)"
            requestBody {
                schema = jsonSchema<IntegrationConversationRequest>()
                description = "The recipient NAV-ident and the conversation to be created"
            }
            responses {
                HttpStatusCode.Created {
                    schema = jsonSchema<no.nav.nks_ai.api.core.conversation.Conversation>()
                    description = "The conversation that got created"
                }
                HttpStatusCode.BadRequest {
                    schema = jsonSchema<IntegrationProblem>()
                    description = "Invalid input"
                }
                HttpStatusCode.Conflict {
                    schema = jsonSchema<IntegrationProblem>()
                    description = "The initial message was saved, but processing did not start. Do not retry."
                }
                HttpStatusCode.UnsupportedMediaType {
                    schema = jsonSchema<IntegrationProblem>()
                    description = "Content-Type must be application/json"
                }
                HttpStatusCode.InternalServerError {
                    schema = jsonSchema<IntegrationProblem>()
                    description = "An unexpected error occurred"
                }
            }
        }
    }
}

private fun ApplicationError.toIntegrationProblem(): IntegrationProblem {
    val conversationId = (this as? ApplicationError.MessageProcessingNotStarted)?.conversationId
    val messageId = (this as? ApplicationError.MessageProcessingNotStarted)?.messageId
    val processingNotStarted = this is ApplicationError.MessageProcessingNotStarted
    return IntegrationProblem(
        title = message,
        status = code.value,
        detail = if (processingNotStarted) {
            "The message was saved, but processing did not start. Do not retry this request."
        } else {
            description
        },
        type = if (processingNotStarted) "urn:nks-bob-api:message-processing-not-started" else "about:blank",
        conversationId = conversationId,
        messageId = messageId,
    )
}

private suspend inline fun <reified T : Any> ApplicationCall.respondIntegrationEither(
    statusCode: HttpStatusCode,
    noinline block: suspend Raise<ApplicationError>.() -> ApplicationResult<T>
) {
    try {
        val result = either { block() }.flatten()
        result.fold(
            ifLeft = { error -> respond(error.code, error.toIntegrationProblem()) },
            ifRight = { value -> respond(statusCode, value) },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: UnsupportedMediaTypeException) {
        respond(
            HttpStatusCode.UnsupportedMediaType,
            IntegrationProblem("Unsupported media type", 415, "Content-Type must be application/json"),
        )
    } catch (_: CannotTransformContentToTypeException) {
        respond(
            HttpStatusCode.UnsupportedMediaType,
            IntegrationProblem("Unsupported media type", 415, "Content-Type must be application/json"),
        )
    } catch (_: BadRequestException) {
        respond(
            HttpStatusCode.BadRequest,
            IntegrationProblem("Invalid request body", 400, "Malformed request body"),
        )
    } catch (exception: Exception) {
        logger.error { "Integration request failed (${exception::class.simpleName})" }
        respond(
            HttpStatusCode.InternalServerError,
            IntegrationProblem("Internal server error", 500, "An unexpected error occurred"),
        )
    }
}
