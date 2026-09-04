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
    val metrics: MetricsConfig,
    val kafka: KafkaConfig,
    ){

    companion object {
    const val HTTP_CLIENT_TIMEOUT_MS = 10 * 60 * 1000

    val conversationsMaxAge: Duration = 30.days
    }
}

// Standard Nais/Aiven Kafka env vars, see https://doc.nais.io/persistence/kafka/
@Serializable
data class KafkaConfig(
    val brokers: String,
    val truststorePath: String = "",
    val keystorePath: String = "",
    val credstorePassword: String = "",
    val conversationEventsTopic: String,
    // Tests (and any environment without a real broker) set this to false so that
    // ConversationEventBus never opens a real producer/consumer or touches the network.
    // Without this, every test spinning up the application would create a Kafka client
    // pointed at an unreachable broker, whose close()/leave-group calls block on network
    // timeouts and make the whole suite hang.
    val enabled: Boolean = true,
) {
    // Locally we run a plaintext single-node broker (see docker-compose.yaml), so there is
    // nothing to configure beyond the broker address. On Nais, mTLS via Aiven-issued
    // certificates is mandatory.
    val securityEnabled: Boolean = truststorePath.isNotBlank()
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
    val corsExtraOrigins: String = "",
) {
    val isRunningOnNais: Boolean = appName.isNotEmpty()

    val corsExtraOriginList: List<String> by lazy {
        corsExtraOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

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

// TODO: Remove when initiator label on answersReceived metric is removed.
@Serializable
data class MetricsConfig(
    val navIdentSecret: String,
)