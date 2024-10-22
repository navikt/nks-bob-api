package no.nav.nks_ai.app

import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import com.typesafe.config.ConfigFactory
import io.github.config4k.extract

object Config {
    val kbs: KbsConfig
    val jwt: JwtConfig
    val db: DbConfig
    val issuers: NonEmptyList<IssuerConfig>

    const val HTTP_CLIENT_TIMEOUT_MS = 60 * 1000

    init {
        ConfigFactory.load()?.let {
            kbs = it.extract<KbsConfig>("kbs")
            jwt = it.extract<JwtConfig>("jwt")
            db = it.extract<DbConfig>("db")
            issuers = it.extract<List<IssuerConfig>>("no.nav.security.jwt.issuers")
                .toNonEmptyListOrNull<IssuerConfig>()
                ?: error("Error reading configuration: No issuers configured.")
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

data class IssuerConfig(
    val issuer_name: String,
    val discoveryurl: String,
    val jwksurl: String,
    val accepted_audience: String,
)