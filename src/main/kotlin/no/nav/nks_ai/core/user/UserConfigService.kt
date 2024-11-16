package no.nav.nks_ai.core.user

import at.favre.lib.crypto.bcrypt.BCrypt
import com.sksamuel.aedile.core.cacheBuilder
import kotlinx.serialization.Serializable
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

class UserConfigService() {
    private val userConfigCache = cacheBuilder<NavIdentCacheKey, UserConfig> {
        expireAfterWrite = 12.hours
    }.build()

    suspend fun getOrCreateUserConfig(navIdent: NavIdent): UserConfig =
        userConfigCache.get(navIdent.plaintext) {
            UserConfigRepo.getUserConfig(navIdent)
                ?: UserConfigRepo.addConfig(defaultUserConfig, navIdent)
        }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: NavIdent): UserConfig? {
        userConfigCache.invalidate(navIdent.plaintext)
        return UserConfigRepo.updateUserConfig(userConfig, navIdent)
    }
}
