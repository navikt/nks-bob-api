package no.nav.nks_ai

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.nav.security.token.support.v2.TokenValidationContextPrincipal
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block)

fun LocalDateTime.Companion.now() =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun ApplicationCall.getClaim(issuer: String, name: String) =
    authentication.principal<TokenValidationContextPrincipal>()
        ?.context
        ?.getClaims(issuer)
        ?.getStringClaim(name)

fun ApplicationCall.getIssuerName(): String? =
    application.environment.config
        .configList("no.nav.security.jwt.issuers")
        .getOrNull(0)
        ?.propertyOrNull("issuer_name")
        ?.getString()

fun ApplicationCall.getNavIdent(): String? =
    getIssuerName()?.let { issuer ->
        getClaim(issuer, "NAVident")
    }