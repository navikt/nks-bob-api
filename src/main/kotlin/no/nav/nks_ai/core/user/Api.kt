package no.nav.nks_ai.core.user

import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.getNavIdent
import no.nav.nks_ai.app.respondError

fun Route.userConfigRoutes(userConfigService: UserConfigService) {
    route("/user/config") {
        get({
            description = "Get the current users config. If it does not exist yet it will be created."
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<UserConfig> {
                        description = "The user config"
                    }
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respondError(ApplicationError.MissingNavIdent())

            userConfigService.getOrCreateUserConfig(navIdent)
                .onLeft { error -> call.respondError(error) }
                .onRight { config -> call.respond(HttpStatusCode.OK, config) }
        }
        patch({
            description = "Patch the current users config."
            request {
                body<PatchUserConfig> {
                    description = "The patched user config"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<UserConfig> {
                        description = "The updated user config"
                    }
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@patch call.respondError(ApplicationError.MissingNavIdent())

            val userConfig = call.receive<PatchUserConfig>()

            userConfigService.patchUserConfig(userConfig, navIdent)
                .onLeft { error -> call.respondError(error) }
                .onRight { config -> call.respond(HttpStatusCode.OK, config) }
        }
        put({
            description = "Update the current users config."
            request {
                body<UserConfig> {
                    description = "The updated user config"
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<UserConfig> {
                        description = "The updated user config"
                    }
                }
            }
        }) {
            val navIdent = call.getNavIdent()
                ?: return@put call.respondError(ApplicationError.MissingNavIdent())

            val userConfig = call.receive<UserConfig>()

            userConfigService.updateUserConfig(userConfig, navIdent)
                .onLeft { error -> call.respondError(error) }
                .onRight { config -> call.respond(HttpStatusCode.OK, config) }
        }
    }
}