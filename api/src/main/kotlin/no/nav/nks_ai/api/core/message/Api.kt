package no.nav.nks_ai.api.core.message

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ErrorResponse
import no.nav.nks_ai.api.app.getNavIdent
import no.nav.nks_ai.api.app.respondError
import no.nav.nks_ai.api.app.respondResult
import no.nav.nks_ai.api.core.feedback.CreateFeedback
import no.nav.nks_ai.api.core.feedback.Feedback
import no.nav.nks_ai.api.core.feedback.FeedbackService

@OptIn(ExperimentalKtorApi::class)
fun Route.messageRoutes(
    messageService: MessageService,
    feedbackService: FeedbackService,
) {
    route("/messages") {
        get("/{id}") {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            val messageId = call.messageId()
                ?: return@get call.respondError(ApplicationError.MissingMessageId())

            call.respondResult(messageService.getMessage(messageId, navIdent))
        }.describe {
            description = "Get a message with the given ID"
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "ID of the message"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Message>()
                    description = "The message requested"
                }
            }
        }
        put("/{id}") {
            val messageId = call.messageId()
                ?: return@put call.respondError(ApplicationError.MissingMessageId())

            val message = call.receive<UpdateMessage>()

            call.respondResult(messageService.updateMessage(messageId, message))
        }.describe {
            description = "Update a message"
            requestBody {
                schema = jsonSchema<UpdateMessage>()
                description = "The updated message"
            }
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "ID of the message"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Message>()
                    description = "The message requested"
                }
            }
        }
        get("/{id}/feedback") {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            val messageId = call.messageId()
                ?: return@get call.respondError(ApplicationError.MissingMessageId())

            call.respondResult(feedbackService.getFeedbacksForMessage(messageId, navIdent))
        }.describe {
            description = "Get the feedback for a message"
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "ID of the message"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<Feedback>>()
                    description = "The feedbacks for the message"

                }
                HttpStatusCode.NotFound {
                    schema = jsonSchema<ErrorResponse>()
                    description = "Message or feedback does not exist"
                }
            }
        }
        post("/{id}/feedback") {
            val navIdent = call.getNavIdent()
                ?: return@post call.respondError(ApplicationError.MissingNavIdent())

            val messageId = call.messageId()
                ?: return@post call.respondError(ApplicationError.MissingMessageId())

            val feedback = call.receive<CreateFeedback>()
            call.respondResult(
                HttpStatusCode.Created,
                feedbackService.addFeedback(messageId, navIdent, feedback)
            )
        }.describe {
            description = "Create a new feedback for a message"
            requestBody {
                schema = jsonSchema<CreateFeedback>()
                description = "The feedback to be created"
            }
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "ID of the message"
                }
            }
            responses {
                HttpStatusCode.Created {
                    schema = jsonSchema<Feedback>()
                    description = "The feedback that got created"
                }
            }
        }
    }
}
