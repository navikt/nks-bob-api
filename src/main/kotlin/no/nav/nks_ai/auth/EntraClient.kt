package no.nav.nks_ai.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

val logger = KotlinLogging.logger { }

class EntraClient(
    private val entraTokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val httpClient: HttpClient,
) {
    suspend fun getOnBehalfOfToken(
        subjectToken: String,
        scope: String
    ): String? {
        val response = httpClient.post(entraTokenUrl) {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("assertion", subjectToken)
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                        append("requested_token_use", "on_behalf_of")
                        append("scope", scope)
                    }
                )
            )
        }

        if (!response.status.isSuccess()) {
            logger.error { "Could not fetch on-behalf-of token: ${response.status.description}" }
            return null
        }

        return response.body<EntraOboResponse>().access_token
    }
}

@Serializable
data class EntraOboResponse(
    val access_token: String,
    val expires_in: Int,
)