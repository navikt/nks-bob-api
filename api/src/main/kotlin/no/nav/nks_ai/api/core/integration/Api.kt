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
import io.ktor.server.routing.query
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationPageRequest
import no.nav.nks_ai.api.core.conversation.conversationId
import no.nav.nks_ai.api.core.message.NewMessage

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
                val navIdent = call.pathNavIdent().bind()
                val newConversation = call.receive<NewConversation>()
                service.createConversation(navIdent, newConversation).bind().right()
            }
        }.describe {
            description = "Create a new conversation on behalf of a NAV-ident (integration)"
            parameters {
                path("navIdent") {
                    schema = jsonSchema<String>()
                    description = "The NAV-ident of the veileder the conversation is created for"
                }
            }
            requestBody {
                schema = jsonSchema<NewConversation>()
                description = "The conversation to be created"
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

        route("/active") {
//            query { TODO
            post {
                call.respondIntegrationEither(HttpStatusCode.OK) {
                    val navIdent = call.pathNavIdent().bind()
                    val request = call.receive<ActiveConversationPageRequest>()
                    service.getActiveConversations(navIdent, request).bind().right()
                }
            }.describe {
                description = "Get all active conversations for a user"
                parameters {
                    path("navIdent") {
                        schema = jsonSchema<String>()
                        description = "The NAV-ident of the veileder that owns the conversation"
                    }
                }
                requestBody {
                    schema = jsonSchema<ActiveConversationPageRequest>()
                    description = "Paginated request"
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<Page<ActiveConversation>>()
                        description = "A page of active conversations"
                    }
                    HttpStatusCode.ServiceUnavailable {
                        schema = jsonSchema<IntegrationProblem>()
                        description = "Could not find active conversations"
                    }
                    HttpStatusCode.BadRequest {
                        schema = jsonSchema<IntegrationProblem>()
                        description = "Malformed request body"
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

        route("/{id}/messages") {
            post {
                call.respondIntegrationEither(HttpStatusCode.Accepted) {
                    val navIdent = call.pathNavIdent().bind()
                    val conversationId = call.conversationId().bind()
                    val newMessage = call.receive<NewMessage>()
                    service.createMessage(navIdent, conversationId, newMessage).bind().right()
                }
            }.describe {
                description = "Create a new message in an existing conversation (integration)"
                parameters {
                    path("navIdent") {
                        schema = jsonSchema<String>()
                        description = "The NAV-ident of the veileder that owns the conversation"
                    }
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "The ID of the conversation"
                    }
                }
                requestBody {
                    schema = jsonSchema<NewMessage>()
                    description = "The message to be created"
                }
                responses {
                    HttpStatusCode.Accepted {
                        schema = jsonSchema<AcceptedIntegrationMessage>()
                        description = "The message was accepted and processing has started"
                    }
                    HttpStatusCode.Conflict {
                        schema = jsonSchema<IntegrationProblem>()
                        description = "The message was saved, but processing did not start. Do not retry."
                    }
                    HttpStatusCode.BadRequest {
                        schema = jsonSchema<IntegrationProblem>()
                        description = "Invalid request"
                    }
                    HttpStatusCode.NotFound {
                        schema = jsonSchema<IntegrationProblem>()
                        description = "Conversation not found"
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
