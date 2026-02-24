package no.nav.nks_ai.core.notification

import io.github.oshai.kotlinlogging.KotlinLogging
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
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.app.respondResult
import no.nav.nks_ai.app.teamLogger

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

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
            val notificationId = call.notificationId()
                ?: return@get call.respondError(ApplicationError.MissingNotificationId())

            call.respondResult(notificationService.getNotification(notificationId))
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
            val createNotification = call.receive<CreateNotification>()
            val navIdent = call.getNavIdent()
                ?: return@post call.respondError(ApplicationError.MissingNavIdent())
            teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=CREATE resource=notification" }

            call.respondResult(notificationService.addNotification(createNotification))
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
                val notificationId = call.notificationId()
                    ?: return@put call.respondError(ApplicationError.MissingNotificationId())

                val createNotification = call.receive<CreateNotification>()
                val navIdent = call.getNavIdent()
                    ?: return@put call.respondError(ApplicationError.MissingNavIdent())
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=UPDATE resource=notification/${notificationId.value}" }

                call.respondResult(notificationService.updateNotification(notificationId, createNotification))
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
                val notificationId = call.notificationId()
                    ?: return@patch call.respondError(ApplicationError.MissingNotificationId())

                val patchNotification = call.receive<PatchNotification>()
                val navIdent = call.getNavIdent()
                    ?: return@patch call.respondError(ApplicationError.MissingNavIdent())
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=PATCH resource=notification/${notificationId.value}" }

                call.respondResult(notificationService.patchNotification(notificationId, patchNotification))
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
                val notificationId = call.notificationId()
                    ?: return@delete call.respondError(ApplicationError.MissingNotificationId())
                val navIdent = call.getNavIdent()
                    ?: return@delete call.respondError(ApplicationError.MissingNavIdent())
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=DELETE resource=notification/${notificationId.value}" }

                call.respondResult(notificationService.deleteNotification(notificationId))
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