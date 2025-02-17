package no.nav.nks_ai.core.message

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.core.HighlightMessageService

fun Route.messageRoutes(
    messageService: MessageService,
    highlightMessageService: HighlightMessageService
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
        post("/{id}/highlight") {
            val messageId = call.messageId()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            highlightMessageService.highlightMessage(messageId)
            call.respond(HttpStatusCode.OK)
        }
    }
}
