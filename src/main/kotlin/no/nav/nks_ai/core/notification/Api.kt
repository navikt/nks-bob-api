package no.nav.nks_ai.core.notification

import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.patch
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.respondError

fun Route.notificationRoutes(notificationService: NotificationService) {
    route("/notifications") {
        get({
            description = "Get all notifications"
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<Notification> {
                        description = "All notifications"
                    }
                }
            }
        }) {
            notificationService.getAllNotifications()
                .onRight { notifications -> call.respond(notifications) }
                .onLeft { error -> call.respondError(error) }
        }
        post({
            description = "Create a notification"
            request {
                body<CreateNotification> {
                    description = "The notification to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The operation was successful"
                    body<Notification> {
                        description = "The created notification"
                    }
                }
            }
        }) {
            val createNotification = call.receiveNullable<CreateNotification>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            notificationService.addNotification(createNotification)
                .onRight { notification -> call.respond(HttpStatusCode.Created, notification) }
                .onLeft { error -> call.respondError(error) }
        }

        route("/{id}") {
            get({
                description = "Get a notification"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the notification"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<Notification> {
                            description = "The requested notification"
                        }
                    }
                }
            }) {
                val notificationId = call.notificationId()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                notificationService.getNotification(notificationId)
                    .onRight { notification -> call.respond(notification) }
                    .onLeft { error -> call.respondError(error) }
            }
            put({
                description = "Update a notification"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the notification"
                    }
                    body<CreateNotification> {
                        description = "The updated notification"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<Notification> {
                            description = "The requested notification"
                        }
                    }
                }
            }) {
                val notificationId = call.notificationId()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)

                val createNotification = call.receiveNullable<CreateNotification>()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)

                notificationService.updateNotification(notificationId, createNotification)
                    .onRight { notification -> call.respond(notification) }
                    .onLeft { error -> call.respondError(error) }
            }
            patch({
                description = "Patch a notification"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the notification"
                    }
                    body<PatchNotification> {
                        description = "The updated notification"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<Notification> {
                            description = "The requested notification"
                        }
                    }
                }
            }) {
                val notificationId = call.notificationId()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest)

                val patchNotification = call.receiveNullable<PatchNotification>()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest)

                notificationService.patchNotification(notificationId, patchNotification)
                    .onRight { notification -> call.respond(notification) }
                    .onLeft { error -> call.respondError(error) }
            }
            delete({
                description = "Delete a notification"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the notification"
                    }
                }
                response {
                    HttpStatusCode.NoContent to {
                        description = "The operation was successful"
                    }
                }
            }) {
                val notificationId = call.notificationId()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                notificationService.deleteNotification(notificationId)
                    .onRight { call.respond(HttpStatusCode.NoContent) }
                    .onLeft { error -> call.respondError(error) }
            }
        }

        route("/news") {
            get({
                description = "Get all notifications with type News"
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<NewsNotification> {
                            description = "All news notifications"
                        }
                    }
                }
            }) {
                notificationService.getNews()
                    .onRight { news -> call.respond(news) }
                    .onLeft { error -> call.respondError(error) }
            }
        }

        route("/errors") {
            get({
                description = "Get all notifications with type Error"
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<ErrorNotification> {
                            description = "All error notifications"
                        }
                    }
                }
            }) {
                notificationService.getErrors()
                    .onRight { errorNotifications -> call.respond(errorNotifications) }
                    .onLeft { error -> call.respondError(error) }
            }
        }
    }
}