package no.nav.nks_ai.core.message

import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ErrorResponse
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.app.respondResult
import no.nav.nks_ai.core.feedback.CreateFeedback
import no.nav.nks_ai.core.feedback.Feedback
import no.nav.nks_ai.core.feedback.FeedbackService

fun Route.messageRoutes(
    messageService: MessageService,
    feedbackService: FeedbackService,
) {
    route("/messages") {
        get("/{id}", {
            description = "Get a message with the given ID"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<Message> {
                        description = "The message requested"
                    }
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            val messageId = call.messageId()
                ?: return@get call.respondError(ApplicationError.MissingMessageId())

            call.respondResult(messageService.getMessage(messageId, navIdent))
        }
        put("/{id}", {
            description = "Update a message"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
                body<UpdateMessage> {
                    description = "The updated message"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<Message> {
                        description = "The message requested"
                    }
                }
            }
        }) {
            val messageId = call.messageId()
                ?: return@put call.respondError(ApplicationError.MissingMessageId())

            val message = call.receive<UpdateMessage>()

            call.respondResult(messageService.updateMessage(messageId, message))
        }
        get("/{id}/feedback", {
            description = "Get the feedback for a message"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The feedback for the message"
                    body<Feedback> {
                        description = "The feedback for the message"
                    }
                }
                HttpStatusCode.NotFound to {
                    description = "Message or feedback does not exist"
                    body<ErrorResponse>()
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            val messageId = call.messageId()
                ?: return@get call.respondError(ApplicationError.MissingMessageId())

            call.respondResult(feedbackService.getFeedbacksForMessage(messageId, navIdent))
        }
        post("/{id}/feedback", {
            description = "Create a new feedback for a message"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
                body<CreateFeedback> {
                    description = "The feedback to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The feedback was created"
                    body<Feedback> {
                        description = "The feedback that got created"
                    }
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@post call.respondError(ApplicationError.MissingNavIdent())

            val messageId = call.messageId()
                ?: return@post call.respondError(ApplicationError.MissingMessageId())

            val feedback = call.receive<CreateFeedback>()
            call.respondResult(
                HttpStatusCode.Created,
                feedbackService.addFeedback(messageId, navIdent, feedback)
            )
        }
    }
}
