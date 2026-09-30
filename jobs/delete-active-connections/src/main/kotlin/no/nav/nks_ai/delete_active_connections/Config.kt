package no.nav.nks_ai.delete_active_connections

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val api: ApiConfig,
    val nais: NaisConfig,
)

@Serializable
data class ApiConfig(
    val url: String,
    val scope: String,
)

@Serializable
data class NaisConfig(
    val tokenEndpoint: String,
)
