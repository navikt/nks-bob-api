package no.nav.nks_ai.shared

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val code: Int,
    val message: String,
    val description: String
)

