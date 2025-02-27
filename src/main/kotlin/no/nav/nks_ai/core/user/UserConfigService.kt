package no.nav.nks_ai.core.user

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import at.favre.lib.crypto.bcrypt.BCrypt
import com.sksamuel.aedile.core.cacheBuilder
import kotlinx.serialization.Serializable
import no.nav.nks_ai.app.DomainError
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
    val showStartInfo: Boolean
)

private val defaultUserConfig = UserConfig(
    showStartInfo = true
)

private typealias NavIdentCacheKey = PlaintextValue

class UserConfigService {
    private val userConfigCache =
        cacheBuilder<NavIdentCacheKey, UserConfig> { // look into replacing with cache4k + arrow.
            expireAfterWrite = 12.hours
        }.build()

    suspend fun getOrCreateUserConfig(navIdent: NavIdent): Either<DomainError, UserConfig> =
        either {
            userConfigCache.get(navIdent.plaintext) {
                UserConfigRepo.getUserConfig(navIdent).getOrElse {
                    UserConfigRepo.addConfig(defaultUserConfig, navIdent).bind()
                }
            }
        }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: NavIdent): Either<DomainError, UserConfig> {
        userConfigCache.invalidate(navIdent.plaintext)
        return UserConfigRepo.updateUserConfig(userConfig, navIdent)
    }
}
