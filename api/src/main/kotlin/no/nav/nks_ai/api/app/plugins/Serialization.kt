package no.nav.nks_ai.api.app.plugins

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.InvalidInputException
import no.nav.nks_ai.api.app.InvalidUuidException
import no.nav.nks_ai.api.app.respondError
import no.nav.nks_ai.api.app.toError

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
    install(SSE)
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    install(StatusPages) {
        exception<BadRequestException> { call, exception ->
            call.respondError(
                ApplicationError.SerializationError(
                    "${exception.message}: ${exception.cause?.message}"
                )
            )
        }
        exception<InvalidUuidException> { call, exception ->
            call.respondError(exception.toError())
        }
        exception<InvalidInputException> { call, exception ->
            call.respondError(exception.toError())
        }
        exception<Throwable> { call, exception ->
            call.respondError(
                ApplicationError.InternalServerError(
                    "Internal Server Error",
                    exception.message ?: "An unknown error occurred"
                )
            )
        }
    }
}
