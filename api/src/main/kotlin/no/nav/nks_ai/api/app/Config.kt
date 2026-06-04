package no.nav.nks_ai.api.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class Config (
    val kbs: KbsConfig,
    val vaskemaskin: VaskemaskinConfig,
    val jwt: JwtConfig,
    val db: DbConfig,
    val nais: NaisConfig,
    val issuer: IssuerConfig,
    @SerialName("bigquery") val bigQuery: BigQueryConfig,
    val unleash: UnleashSettings,
    ){

    companion object {
    const val HTTP_CLIENT_TIMEOUT_MS = 10 * 60 * 1000

    val conversationsMaxAge: Duration = 30.days
    }
}

@Serializable
data class KbsConfig(
    val url: String,
    val scope: String,
)

@Serializable
data class VaskemaskinConfig(
    val url: String,
    val scope: String,
)

@Serializable
data class JwtConfig(
    val clientId: String,
    val clientSecret: String,
    val configTokenEndpoint: String,
    val adminGroup: String,
)

@Serializable
data class DbConfig(
    val username: String,
    val password: String,
    val database: String,
    val host: String,
    val port: String,
    val jdbcURL: String?,
)

@Serializable
data class NaisConfig(
    val electorUrl: String,
    val appName: String,
    val tokenEndpoint: String,
    val preAuthorizedApps: String = "",
) {
    val isRunningOnNais: Boolean = appName.isNotEmpty()

    val preAuthorizedAppList: List<PreAuthorizedApp> by lazy {
        if (preAuthorizedApps.isBlank()) return@lazy emptyList()
        runCatching { Json.decodeFromString<List<PreAuthorizedApp>>(preAuthorizedApps) }
            .getOrDefault(emptyList())
    }
}

@Serializable
data class PreAuthorizedApp(val name: String, val clientId: String)

@Serializable
data class IssuerConfig(
    val issuer_name: String,
    val discoveryurl: String,
    val jwksurl: String,
    val accepted_audience: String,
)

@Serializable
data class BigQueryConfig(
    val projectId: String,
    val kunnskapsbaseDataset: String,
    val kunnskapsartiklerTable: String,
    val testgrunnlagDataset: String,
    val stjernemarkerteSvarTable: String,
)

@Serializable
data class UnleashSettings(
    val serverApiUrl: String,
    val serverApiToken: String,
    val appName: String,
) {
    val isConfigured: Boolean get() = serverApiUrl.isNotEmpty()
}