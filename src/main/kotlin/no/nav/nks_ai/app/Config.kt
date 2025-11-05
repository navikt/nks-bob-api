package no.nav.nks_ai.app

import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import com.typesafe.config.ConfigFactory
import io.github.config4k.extract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

object Config {
    val kbs: KbsConfig
    val jwt: JwtConfig
    val db: DbConfig
    val nais: NaisConfig
    val issuers: NonEmptyList<IssuerConfig>
    val bigQuery: BigQueryConfig

    const val HTTP_CLIENT_TIMEOUT_MS = 10 * 60 * 1000

    val conversationsMaxAge: Duration = 30.days

    init {
        ConfigFactory.load()?.let {
            kbs = it.extract<KbsConfig>("kbs")
            jwt = it.extract<JwtConfig>("jwt")
            db = it.extract<DbConfig>("db")
            nais = it.extract<NaisConfig>("nais")
            issuers = it.extract<List<IssuerConfig>>("no.nav.security.jwt.issuers")
                .toNonEmptyListOrNull<IssuerConfig>()
                ?: error("Error reading configuration: No issuers configured.")
            bigQuery = it.extract<BigQueryConfig>("bigquery")
        } ?: error("Error reading configuration")
    }
}

data class KbsConfig(
    val url: String,
    val scope: String,
)

data class JwtConfig(
    val clientId: String,
    val clientSecret: String,
    val configTokenEndpoint: String,
    val adminGroup: String,
)

data class DbConfig(
    val username: String,
    val password: String,
    val database: String,
    val host: String,
    val port: String,
    val jdbcURL: String?,
)

data class NaisConfig(
    val electorUrl: String,
    val appName: String,
) {
    val isRunningOnNais: Boolean = appName.isNotEmpty()
}

data class IssuerConfig(
    val issuer_name: String,
    val discoveryurl: String,
    val jwksurl: String,
    val accepted_audience: String,
)

data class BigQueryConfig(
    val projectId: String,
    val kunnskapsbaseDataset: String,
    val kunnskapsartiklerTable: String,
    val testgrunnlagDataset: String,
    val stjernemarkerteSvarTable: String,
)