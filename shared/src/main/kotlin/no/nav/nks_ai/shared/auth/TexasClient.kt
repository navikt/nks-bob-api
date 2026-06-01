package no.nav.nks_ai.shared.auth

import arrow.core.Either
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TexasClient(
    private val naisTokenEndpoint: String,
    private val httpClient: HttpClient,
    private val logger: KLogger
) {
    suspend fun getMachineToken(targetAudience: String): Either<TexasError, String> = either {
        createMachineToken(targetAudience).bind().accessToken
    }

    private suspend fun createMachineToken(
        targetAudience: String
    ): Either<TexasError, TexasTokenResponse> = either {
        val response = runCatching {
            httpClient.post(naisTokenEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(
                    TexasTokenRequest(
                        target = targetAudience,
                        identityProvider = "entra_id"
                    )
                )
            }
        }.getOrElse { e ->
            logger.error(e) { "Could not fetch machine token: request failed" }
            raise(
                TexasError(
                    code = 500,
                    message = "Could not fetch machine token",
                    description = e.message ?: "Request to token endpoint failed"
                )
            )
        }

        if (!response.status.isSuccess()) {
            logger.error { "Could not fetch machine token: ${response.status.value} (${response.status.description})" }
            raise(
                TexasError(
                    code = response.status.value,
                    message = "Could not fetch machine token",
                    description = response.status.description
                )
            )
        }

        runCatching { response.body<TexasTokenResponse>() }
            .getOrElse { e ->
                logger.error(e) { "Could not parse machine token response" }
                raise(
                    TexasError(
                        code = 500,
                        message = "Could not fetch machine token",
                        description = e.message ?: "Could not parse token response"
                    )
                )
            }
    }
}

@Serializable
data class TexasTokenRequest(
    val target: String,
    @SerialName("identity_provider") val identityProvider: String,
)

@Serializable
data class TexasTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("token_type") val tokenType: String,
)

data class TexasError(
    val code: Int,
    val message: String,
    val description: String
)