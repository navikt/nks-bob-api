package no.nav.nks_ai.api.core.notification

import arrow.core.raise.context.bind
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.plugins.logAdminAccess
import no.nav.nks_ai.api.app.respondEither
import no.nav.nks_ai.api.app.respondError
import no.nav.nks_ai.api.app.respondResult

@OptIn(ExperimentalKtorApi::class)
fun Route.notificationUserRoutes(notificationService: NotificationService) {
    route("/notifications") {
        get {
            notificationService.getAllNotifications()
                .onRight { notifications -> call.respond(notifications) }
                .onLeft { error -> call.respondError(error) }
        }.describe {
            description = "Get all notifications"
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<Notification>>()
                    description = "All notifications"
                }
            }
        }

        get("/news") {
            call.respondResult(notificationService.getNews())
        }.describe {
            description = "Get all notifications with type News"
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<NewsNotification>>()
                    description = "All news notifications"
                }
            }
        }

        get("/errors") {
            call.respondResult(notificationService.getErrors())
        }.describe {
            description = "Get all notifications with type Error"
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<List<ErrorNotification>>()
                    description = "All error notifications"
                }
            }
        }

        get("/{id}") {
            call.respondEither {
                val notificationId = call.notificationId().bind()
                notificationService.getNotification(notificationId)
            }
        }.describe {
            description = "Get a notification"
            parameters {
                path("id") {
                    schema = jsonSchema<String>()
                    description = "ID of the notification"
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Notification>()
                    description = "The requested notification"
                }
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
fun Route.notificationAdminRoutes(notificationService: NotificationService) {
    route("/admin/notifications") {
        post {
            call.respondEither(HttpStatusCode.Created) {
                val createNotification = call.receive<CreateNotification>()
                call.logAdminAccess().bind()

                notificationService.addNotification(createNotification)
            }
        }.describe {
            description = "Create a notification"
            requestBody {
                schema = jsonSchema<CreateNotification>()
                description = "The notification to be created"
            }
            responses {
                HttpStatusCode.Created {
                    schema = jsonSchema<Notification>()
                    description = "The created notification"
                }
            }
        }

        route("/{id}") {
            put {
                call.respondEither {
                    val notificationId = call.notificationId().bind()
                    val createNotification = call.receive<CreateNotification>()
                    call.logAdminAccess().bind()

                    notificationService.updateNotification(notificationId, createNotification)
                }
            }.describe {
                description = "Update a notification"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the notification"
                    }
                }
                requestBody {
                    schema = jsonSchema<CreateNotification>()
                    description = "The updated notification"
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<Notification>()
                        description = "The requested notification"
                    }
                }
            }
            patch {
                call.respondEither {
                    val notificationId = call.notificationId().bind()
                    val patchNotification = call.receive<PatchNotification>()
                    call.logAdminAccess().bind()

                    notificationService.patchNotification(notificationId, patchNotification)
                }
            }.describe {
                description = "Patch a notification"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the notification"
                    }
                }
                requestBody {
                    schema = jsonSchema<PatchNotification>()
                    description = "The updated notification"
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<Notification>()
                        description = "The requested notification"
                    }
                }
            }
            delete {
                call.respondEither(HttpStatusCode.NoContent) {
                    val notificationId = call.notificationId().bind()
                    call.logAdminAccess().bind()

                    notificationService.deleteNotification(notificationId)
                }
            }.describe {
                description = "Delete a notification"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the notification"
                    }
                }
                responses {
                    HttpStatusCode.NoContent {
                        description = "The operation was successful"
                    }
                }
            }
        }
    }
}