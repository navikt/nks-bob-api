package no.nav.nks_ai.core.notification

import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.respondError

fun Route.notificationUserRoutes(notificationService: NotificationService) {
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

        get("/news", {
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

        get("/errors", {
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

        get("/{id}", {
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
                ?: return@get call.respondError(ApplicationError.MissingNotificationId())

            notificationService.getNotification(notificationId)
                .onRight { notification -> call.respond(notification) }
                .onLeft { error -> call.respondError(error) }
        }
    }
}

fun Route.notificationAdminRoutes(notificationService: NotificationService) {
    route("/admin/notifications") {
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
            val createNotification = call.receive<CreateNotification>()

            notificationService.addNotification(createNotification)
                .onRight { notification -> call.respond(HttpStatusCode.Created, notification) }
                .onLeft { error -> call.respondError(error) }
        }

        route("/{id}") {
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
                    ?: return@put call.respondError(ApplicationError.MissingNotificationId())

                val createNotification = call.receive<CreateNotification>()

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
                    ?: return@patch call.respondError(ApplicationError.MissingNotificationId())

                val patchNotification = call.receive<PatchNotification>()

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
                    ?: return@delete call.respondError(ApplicationError.MissingNotificationId())

                notificationService.deleteNotification(notificationId)
                    .onRight { call.respond(HttpStatusCode.NoContent) }
                    .onLeft { error -> call.respondError(error) }
            }
        }
    }
}