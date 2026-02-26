package no.nav.nks_ai.delete_conversations

import com.typesafe.config.ConfigFactory
import io.github.config4k.extract

object Config {
    val api: ApiConfig
    val jwt: JwtConfig

    init {
        ConfigFactory.load()?.let {
            api = it.extract<ApiConfig>("api")
            jwt = it.extract<JwtConfig>("jwt")
        } ?: error("Error reading configuration")
    }
}

data class ApiConfig(
    val url: String,
    val scope: String,
)

data class JwtConfig(
    val clientId: String,
    val clientSecret: String,
    val configTokenEndpoint: String,
)