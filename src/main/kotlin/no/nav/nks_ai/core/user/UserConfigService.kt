package no.nav.nks_ai.core.user

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.serialization.Serializable

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

class UserConfigService() {
    suspend fun getOrCreateUserConfig(navIdent: NavIdent): UserConfig {
        return UserConfigRepo.getUserConfig(navIdent)
            ?: UserConfigRepo.addConfig(defaultUserConfig, navIdent)
    }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: NavIdent): UserConfig? {
        return UserConfigRepo.updateUserConfig(userConfig, navIdent)
    }
}
