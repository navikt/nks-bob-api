package no.nav.nks_ai.core.message

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.core.MarkMessageStarredService

fun Route.messageRoutes(
    messageService: MessageService,
    markMessageStarredService: MarkMessageStarredService
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
            val messageId = call.messageId()
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val message = messageService.getMessage(messageId)
            if (message == null) {
                return@get call.respond(HttpStatusCode.NotFound)
            }

            call.respond(message)
        }
        post("/{id}/feedback", {
            description = "Create a new feedback for a message"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
                body<NewFeedback> {
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
            val messageId = call.messageId()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val feedback = call.receiveNullable<NewFeedback>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val message = messageService.addFeedbackToMessage(messageId, feedback)
            if (message == null) {
                return@post call.respond(HttpStatusCode.NotFound)
            }

            call.respond(HttpStatusCode.Created, message)
        }
        post("/{id}/star", {
            description = "Star a message"
            request {
                pathParameter<String>("id") {
                    description = "ID of the message"
                }
                body<Unit> {
                    description = "No body required"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The message was starred"
                    body<Unit> {
                        description = "No body in response"
                    }
                }
            }
        }) {
            val messageId = call.messageId()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            markMessageStarredService.markStarred(messageId)
                .fold(
                    { call.respondError(it) },
                    { call.respond(HttpStatusCode.OK) },
                )
        }
    }
}
