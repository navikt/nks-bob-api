package no.nav.nks_ai.delete_ignored_words

import kotlinx.serialization.Serializable

@Serializable
data class Config (
    val api: ApiConfig,
    val jwt: JwtConfig,
    val nais: NaisConfig,
)

@Serializable
data class ApiConfig(
    val url: String,
    val scope: String,
)

@Serializable
data class JwtConfig(
    val clientId: String,
    val clientSecret: String,
    val configTokenEndpoint: String,
)

@Serializable
data class NaisConfig(
    val tokenEndpoint: String,
)
