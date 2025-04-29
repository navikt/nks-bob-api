package no.nav.nks_ai.core.user

import arrow.core.Either
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.raise.either
import at.favre.lib.crypto.bcrypt.BCrypt
import com.sksamuel.aedile.core.cacheBuilder
import kotlinx.serialization.Serializable
import no.nav.nks_ai.app.ApplicationError
import kotlin.time.Duration.Companion.hours

@JvmInline
internal value class PlaintextValue(val value: String)

class NavIdent(value: String) {
    // Should only be used when verifying with a hash.
    internal val plaintext: PlaintextValue = PlaintextValue(value)

    val hash: String by lazy {
        BCrypt.withDefaults().hashToString(6, value.toCharArray())
    }

    override fun toString(): String = "NavIdent($hash)"
}

@Serializable
data class UserConfig(
    val showStartInfo: Boolean,
    val showTutorial: Boolean,
    val showNewConceptInfo: Boolean,
)

@Serializable
data class PatchUserConfig(
    val showStartInfo: Boolean? = null,
    val showTutorial: Boolean? = null,
    val showNewConceptInfo: Boolean? = null,
)

private val defaultUserConfig = UserConfig(
    showStartInfo = true,
    showTutorial = true,
    showNewConceptInfo = false,
)

private typealias NavIdentCacheKey = PlaintextValue

class UserConfigService {
    private val userConfigCache =
        cacheBuilder<NavIdentCacheKey, UserConfig> { // look into replacing with cache4k + arrow.
            expireAfterWrite = 12.hours
        }.build()

    suspend fun getOrCreateUserConfig(navIdent: NavIdent): Either<ApplicationError, UserConfig> =
        either {
            userConfigCache.get(navIdent.plaintext) {
                UserConfigRepo.getUserConfig(navIdent).getOrElse {
                    UserConfigRepo.addConfig(defaultUserConfig, navIdent).bind()
                }
            }
        }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: NavIdent): Either<ApplicationError, UserConfig> {
        userConfigCache.invalidate(navIdent.plaintext)
        return UserConfigRepo.updateUserConfig(userConfig, navIdent)
    }

    suspend fun patchUserConfig(userConfig: PatchUserConfig, navIdent: NavIdent): Either<ApplicationError, UserConfig> {
        userConfigCache.invalidate(navIdent.plaintext)
        return UserConfigRepo.patchUserConfig(
            navIdent = navIdent,
            showStartInfo = Option.fromNullable(userConfig.showStartInfo),
            showTutorial = Option.fromNullable(userConfig.showTutorial),
            showNewConceptInfo = Option.fromNullable(userConfig.showNewConceptInfo),
        )
    }
}
