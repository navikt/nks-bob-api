package no.nav.nks_ai.core.user

import kotlinx.serialization.Serializable

@JvmInline
value class NavIdent(val value: String)

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
