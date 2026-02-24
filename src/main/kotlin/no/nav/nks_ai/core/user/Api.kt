package no.nav.nks_ai.core.user

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
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

@OptIn(ExperimentalKtorApi::class)
fun Route.userConfigRoutes(userConfigService: UserConfigService) {
    route("/user/config") {
        get {
            call.respondEither {
                val navIdent = call.navIdent().bind()

                userConfigService.getOrCreateUserConfig(navIdent)
                    .map { it.asDto(isAdmin()) }
            }
        }.describe {
            description = "Get the current users config. If it does not exist yet it will be created."
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<UserConfigDto>()
                    description = "The user config"
                }
            }
        }
        patch {
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val userConfig = call.receive<PatchUserConfig>()

                userConfigService.patchUserConfig(userConfig, navIdent)
                    .map { it.asDto(isAdmin()) }
            }
        }.describe {
            description = "Patch the current users config."
            requestBody {
                schema = jsonSchema<PatchUserConfig>()
                description = "The patched user config"
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<UserConfigDto>()
                    description = "The updated user config"
                }
            }
        }
        put {
            call.respondEither {
                val navIdent = call.navIdent().bind()
                val userConfig = call.receive<UserConfig>()

                userConfigService.updateUserConfig(userConfig, navIdent)
                    .map { it.asDto(isAdmin()) }
            }
        }.describe {
            description = "Update the current users config."
            requestBody {
                schema = jsonSchema<UserConfig>()
                description = "The updated user config"
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<UserConfigDto>()
                    description = "The updated user config"
                }
            }
        }
    }
}