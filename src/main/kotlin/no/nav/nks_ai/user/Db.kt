package no.nav.nks_ai.user

import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.conversation.Conversations
import no.nav.nks_ai.now
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

internal object UserConfigs : UUIDTable("user_configs") {
    val navIdent = varchar("nav_ident", 255).uniqueIndex()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val showStartInfo = bool("show_start_info").default(true)
}

internal class UserConfigDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserConfigDAO>(UserConfigs)

    var navIdent by UserConfigs.navIdent
    var createdAt by Conversations.createdAt
    var showStartInfo by UserConfigs.showStartInfo
}

internal fun UserConfigDAO.Companion.findByNavIdent(navIdent: String): UserConfigDAO? =
    find {
        UserConfigs.navIdent eq navIdent
    }.firstOrNull()

internal fun UserConfigDAO.toModel() = UserConfig(
    showStartInfo = showStartInfo,
)

object UserConfigRepo {
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