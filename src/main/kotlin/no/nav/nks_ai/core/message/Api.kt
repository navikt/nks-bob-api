package no.nav.nks_ai.core.message

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.respondError

fun Route.messageRoutes(
    messageService: MessageService,
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

            messageService.getMessage(messageId)
                .onLeft { error -> call.respondError(error) }
                .onRight { message -> call.respond(message) }
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
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val message = call.receiveNullable<UpdateMessage>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            messageService.updateMessage(messageId, message)
                .onLeft { error -> call.respondError(error) }
                .onRight { call.respond(it) }
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

            messageService.addFeedbackToMessage(messageId, feedback)
                .onLeft { error -> call.respondError(error) }
                .onRight { message -> call.respond(message) }
        }
    }
}
