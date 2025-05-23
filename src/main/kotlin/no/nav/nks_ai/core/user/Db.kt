package no.nav.nks_ai.core.user

import arrow.core.None
import arrow.core.Option
import arrow.core.raise.either
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.ApplicationResult
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
    val showNewConceptInfo = bool("show_new_concept_info").default(false)
}

internal class UserConfigDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserConfigDAO>(UserConfigs)

    var navIdent by UserConfigs.navIdent
    var createdAt by UserConfigs.createdAt
    var showStartInfo by UserConfigs.showStartInfo
    var showTutorial by UserConfigs.showTutorial
    var showNewConceptInfo by UserConfigs.showNewConceptInfo
}

internal fun UserConfigDAO.Companion.findByNavIdent(navIdent: NavIdent): UserConfigDAO? =
    find {
        UserConfigs.navIdent eq navIdent.plaintext.value
    }.firstOrNull()

internal fun UserConfigDAO.toModel() = UserConfig(
    showStartInfo = showStartInfo,
    showTutorial = showTutorial,
    showNewConceptInfo = showNewConceptInfo,
)

object UserConfigRepo {
    suspend fun getUserConfig(navIdent: NavIdent): ApplicationResult<UserConfig> =
        suspendTransaction {
            either {
                UserConfigDAO.findByNavIdent(navIdent)?.toModel()
                    ?: raise(ApplicationError.UserConfigNotFound())
            }
        }

    suspend fun addConfig(config: UserConfig, navIdent: NavIdent): ApplicationResult<UserConfig> =
        suspendTransaction {
            either {
                UserConfigDAO.new {
                    this.navIdent = navIdent.plaintext.value
                    this.showStartInfo = config.showStartInfo
                    this.showTutorial = config.showTutorial
                    this.showNewConceptInfo = config.showNewConceptInfo
                }.toModel()
            }
        }

    suspend fun updateUserConfig(config: UserConfig, navIdent: NavIdent): ApplicationResult<UserConfig> =
        suspendTransaction {
            either {
                UserConfigDAO
                    .findByNavIdent(navIdent)
                    ?.apply {
                        showStartInfo = config.showStartInfo
                        showTutorial = config.showTutorial
                        showNewConceptInfo = config.showNewConceptInfo
                    }
                    ?.toModel()
                    ?: raise(ApplicationError.UserConfigNotFound())
            }
        }

    suspend fun patchUserConfig(
        navIdent: NavIdent,
        showStartInfo: Option<Boolean> = None,
        showTutorial: Option<Boolean> = None,
        showNewConceptInfo: Option<Boolean> = None,
    ) =
        suspendTransaction {
            either {
                UserConfigDAO
                    .findByNavIdent(navIdent)
                    ?.also { entity ->
                        showStartInfo.onSome { entity.showStartInfo = it }
                        showTutorial.onSome { entity.showTutorial = it }
                        showNewConceptInfo.onSome { entity.showNewConceptInfo = it }
                    }
                    ?.toModel()
                    ?: raise(ApplicationError.UserConfigNotFound())
            }
        }
}