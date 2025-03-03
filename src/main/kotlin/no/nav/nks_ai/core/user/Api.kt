package no.nav.nks_ai.core.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.patch
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
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
                ?: return@get call.respond(HttpStatusCode.Forbidden)

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
                ?: return@patch call.respond(HttpStatusCode.Forbidden)

            val userConfig = call.receiveNullable<PatchUserConfig>()
                ?: return@patch call.respond(HttpStatusCode.BadRequest)

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
                ?: return@put call.respond(HttpStatusCode.Forbidden)

            val userConfig = call.receiveNullable<UserConfig>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            userConfigService.updateUserConfig(userConfig, navIdent)
                .onLeft { error -> call.respondError(error) }
                .onRight { config -> call.respond(HttpStatusCode.OK, config) }
        }
    }
}