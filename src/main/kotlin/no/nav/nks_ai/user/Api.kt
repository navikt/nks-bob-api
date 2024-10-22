package no.nav.nks_ai.user

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import no.nav.nks_ai.getNavIdent

fun Route.userConfigRoutes(userConfigService: UserConfigService) {
    route("/user/config") {
        get(/* {
            description = "Get the current users config. If it does not exist yet it will be created."
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<UserConfig> {
                        description = "The user config"
                    }
                }
            }
        } */
        ) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            val config = userConfigService.getOrCreateUserConfig(navIdent)
            call.respond(HttpStatusCode.OK, config)
        }
        put(/* {
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
        } */
        ) {
            val navIdent = call.getNavIdent()
                ?: return@put call.respond(HttpStatusCode.Forbidden)

            val userConfig = call.receiveNullable<UserConfig>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val updatedUserConfig = userConfigService.updateUserConfig(userConfig, navIdent)
                ?: return@put call.respond(HttpStatusCode.NotFound)

            call.respond(HttpStatusCode.OK, updatedUserConfig)
        }
    }
}