package no.nav.nks_ai.user

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.conversation.Conversations
import no.nav.nks_ai.getNavIdent
import no.nav.nks_ai.now
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

object UserConfigs : UUIDTable("user_configs") {
    val navIdent = varchar("nav_ident", 255).uniqueIndex()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val showStartInfo = bool("show_start_info").default(true)
}

class UserConfigDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserConfigDAO>(UserConfigs)

    var navIdent by UserConfigs.navIdent
    var createdAt by Conversations.createdAt
    var showStartInfo by UserConfigs.showStartInfo
}

fun UserConfigDAO.Companion.findByNavIdent(navIdent: String): UserConfigDAO? =
    find {
        UserConfigs.navIdent eq navIdent
    }.firstOrNull()

fun UserConfigDAO.toModel() = UserConfig(
    showStartInfo = showStartInfo,
)

@Serializable
data class UserConfig(
    val showStartInfo: Boolean
)

private val defaultUserConfig = UserConfig(
    showStartInfo = true
)

class UserConfigRepo() {
    suspend fun getUserConfig(navIdent: String): UserConfig? =
        suspendTransaction {
            UserConfigDAO.findByNavIdent(navIdent)?.toModel()
        }

    suspend fun addConfig(config: UserConfig, navIdent: String): UserConfig =
        suspendTransaction {
            UserConfigDAO.new {
                this.navIdent = navIdent
                this.showStartInfo = config.showStartInfo
            }.toModel()
        }

    suspend fun updateUserConfig(config: UserConfig, navIdent: String): UserConfig? =
        suspendTransaction {
            UserConfigDAO
                .findByNavIdent(navIdent)
                ?.apply { showStartInfo = config.showStartInfo }
                ?.toModel()
        }
}

class UserConfigService(private val userConfigRepo: UserConfigRepo) {
    suspend fun getOrCreateUserConfig(navIdent: String): UserConfig {
        return userConfigRepo.getUserConfig(navIdent)
            ?: userConfigRepo.addConfig(defaultUserConfig, navIdent)
    }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: String): UserConfig? {
        return userConfigRepo.updateUserConfig(userConfig, navIdent)
    }
}

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

            val config = userConfigService.getOrCreateUserConfig(navIdent)
            call.respond(HttpStatusCode.OK, config)
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

            val updatedUserConfig = userConfigService.updateUserConfig(userConfig, navIdent)
                ?: return@put call.respond(HttpStatusCode.NotFound)

            call.respond(HttpStatusCode.OK, updatedUserConfig)
        }
    }
}