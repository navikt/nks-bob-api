package no.nav.nks_ai.core.user

import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.nks_ai.app.navIdent
import no.nav.nks_ai.app.plugins.isAdmin
import no.nav.nks_ai.app.respondEither

enum class UserType {
    @SerialName("user")
    User,

    @SerialName("admin")
    Admin
}

@Serializable
data class UserConfigDto(
    val showStartInfo: Boolean,
    val showTutorial: Boolean,
    val showNewConceptInfo: Boolean,
    val userType: UserType,
)

fun UserConfig.asDto(isAdmin: Boolean) =
    UserConfigDto(
        showStartInfo = showStartInfo,
        showTutorial = showTutorial,
        showNewConceptInfo = showNewConceptInfo,
        userType = when (isAdmin) {
            true -> UserType.Admin
            false -> UserType.User
        }
    )

fun Route.userConfigRoutes(userConfigService: UserConfigService) {
    route("/user/config") {
        get({
            description = "Get the current users config. If it does not exist yet it will be created."
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<UserConfigDto> {
                        description = "The user config"
                    }
                }
            }
        }) {
            call.respondEither {
                val navIdent = call.navIdent().bind()

                userConfigService.getOrCreateUserConfig(navIdent)
                    .map { it.asDto(isAdmin()) }
            }
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
                    body<UserConfigDto> {
                        description = "The updated user config"
                    }
                }
            }
        }) {
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val userConfig = call.receive<PatchUserConfig>()

                userConfigService.patchUserConfig(userConfig, navIdent)
                    .map { it.asDto(isAdmin()) }
            }
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
                    body<UserConfigDto> {
                        description = "The updated user config"
                    }
                }
            }
        }) {
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val userConfig = call.receive<UserConfig>()

                userConfigService.updateUserConfig(userConfig, navIdent)
                    .map { it.asDto(isAdmin()) }
            }
        }
    }
}