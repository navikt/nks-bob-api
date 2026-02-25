package no.nav.nks_ai.api.core.user

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.raise.either
import at.favre.lib.crypto.bcrypt.BCrypt
import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.eitherGet
import kotlin.time.Duration.Companion.hours

@JvmInline
internal value class PlaintextValue(val value: String)

class NavIdent(value: String) {
    // Should only be used when verifying with a hash.
    internal val plaintext: PlaintextValue = PlaintextValue(value)

    val hash: String by lazy {
        BCrypt.withDefaults().hashToString(6, value.toCharArray())
    }

    private val verifyer = BCrypt.verifyer()
    fun isVerified(hash: String): Boolean = verifyer
        .verify(plaintext.value.toCharArray(), hash.toCharArray())
        .verified

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
        Caffeine.newBuilder()
            .expireAfterWrite(12.hours)
            .asCache<NavIdentCacheKey, UserConfig>()

    suspend fun getOrCreateUserConfig(navIdent: NavIdent): ApplicationResult<UserConfig> =
        userConfigCache.eitherGet(navIdent.plaintext) {
            either {
                UserConfigRepo.getUserConfig(navIdent).getOrElse {
                    UserConfigRepo.addConfig(defaultUserConfig, navIdent).bind()
                }
            }
        }

    suspend fun updateUserConfig(userConfig: UserConfig, navIdent: NavIdent): ApplicationResult<UserConfig> {
        userConfigCache.invalidate(navIdent.plaintext)
        return UserConfigRepo.updateUserConfig(userConfig, navIdent)
            .onRight { userConfigCache.put(navIdent.plaintext, it) }
    }

    suspend fun patchUserConfig(userConfig: PatchUserConfig, navIdent: NavIdent): ApplicationResult<UserConfig> {
        userConfigCache.invalidate(navIdent.plaintext)
        return UserConfigRepo.patchUserConfig(
            navIdent = navIdent,
            showStartInfo = Option.fromNullable(userConfig.showStartInfo),
            showTutorial = Option.fromNullable(userConfig.showTutorial),
            showNewConceptInfo = Option.fromNullable(userConfig.showNewConceptInfo),
        ).onRight { userConfigCache.put(navIdent.plaintext, it) }
    }
}
