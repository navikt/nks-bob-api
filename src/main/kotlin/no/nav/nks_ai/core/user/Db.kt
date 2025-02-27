package no.nav.nks_ai.core.user

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.DomainError
import no.nav.nks_ai.app.bcryptVerified
import no.nav.nks_ai.app.now
import no.nav.nks_ai.app.suspendTransaction
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
    val showTutorial = bool("show_tutorial").default(true)
}

internal class UserConfigDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserConfigDAO>(UserConfigs)

    var navIdent by UserConfigs.navIdent
    var createdAt by UserConfigs.createdAt
    var showStartInfo by UserConfigs.showStartInfo
    var showTutorial by UserConfigs.showTutorial
}

internal fun UserConfigDAO.Companion.findByNavIdent(navIdent: NavIdent): UserConfigDAO? =
    find {
        UserConfigs.navIdent bcryptVerified navIdent
    }.firstOrNull()

internal fun UserConfigDAO.toModel() = UserConfig(
    showStartInfo = showStartInfo,
    showTutorial = showTutorial,
)

object UserConfigRepo {
    suspend fun getUserConfig(navIdent: NavIdent): Either<DomainError, UserConfig> =
        suspendTransaction {
            either {
                UserConfigDAO.findByNavIdent(navIdent)?.toModel()
                    ?: raise(DomainError.UserConfigNotFound())
            }
        }

    suspend fun addConfig(config: UserConfig, navIdent: NavIdent): Either<DomainError, UserConfig> =
        suspendTransaction {
            either {
                UserConfigDAO.new {
                    this.navIdent = navIdent.hash
                    this.showStartInfo = config.showStartInfo
                }.toModel()
            }
        }

    suspend fun updateUserConfig(config: UserConfig, navIdent: NavIdent): Either<DomainError, UserConfig> =
        suspendTransaction {
            either {
                UserConfigDAO
                    .findByNavIdent(navIdent)
                    ?.apply { showStartInfo = config.showStartInfo }
                    ?.toModel()
                    ?: raise(DomainError.UserConfigNotFound())
            }
        }
}