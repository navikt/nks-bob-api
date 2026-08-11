package no.nav.nks_ai.api.app.plugins

import arrow.core.getOrElse
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.events.EventDefinition
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.navIdent
import no.nav.nks_ai.api.app.teamLogger

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

private val AdminAccessEvent: EventDefinition<ApplicationCall> = EventDefinition()

fun ApplicationCall.logAdminAccess(): ApplicationResult<Unit> = either {
    navIdent().bind() // Ensure that navIdent is present
    application.monitor.raise(AdminAccessEvent, this@logAdminAccess)
}

fun Application.configureAdminLogging() {
    monitor.subscribe(AdminAccessEvent) { call ->
        val navIdent = call.navIdent().getOrElse {
            logger.error { "Missing navIdent in admin access log" }
            return@subscribe
        }

        val resource = call.request.path()
        val action = when (call.request.httpMethod) {
            HttpMethod.Get -> "READ"
            HttpMethod.Post -> "CREATE"
            HttpMethod.Put -> "UPDATE"
            HttpMethod.Delete -> "DELETE"
            HttpMethod.Patch -> "PATCH"
            else -> call.request.httpMethod.value
        }

        teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=${action} resource=${resource}" }
    }
}
