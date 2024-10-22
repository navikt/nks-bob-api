package no.nav.nks_ai.core.user

import kotlinx.serialization.Serializable

@Serializable
data class UserConfig(
    val showStartInfo: Boolean
)

private val defaultUserConfig = UserConfig(
    showStartInfo = true
)

class UserConfigService() {
    suspend fun getOrCreateUserConfig(navIdent: String): UserConfig {
        return UserConfigRepo.getUserConfig(navIdent)
            ?: UserConfigRepo.addConfig(defaultUserConfig, navIdent)
    }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: String): UserConfig? {
        return UserConfigRepo.updateUserConfig(userConfig, navIdent)
    }
}
