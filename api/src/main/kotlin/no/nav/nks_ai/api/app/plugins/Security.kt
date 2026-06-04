package no.nav.nks_ai.api.app.plugins

import com.auth0.jwk.JwkProviderBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.RoutingContext
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.getConfig
import no.nav.nks_ai.api.app.respondError
import java.net.URI
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

fun Application.configureSecurity() {
// NOTE: Not compatible with Ktor 3.0.0 at the moment.
//    install(Authentication) {
////        tokenValidationSupport(
////            config = this@configureSecurity.environment.config,
////            resourceRetriever = DefaultResourceRetriever()
////        )
//
////        tokenValidationSupport(
////            name = "AdminUser",
////            config = this@configureSecurity.environment.config,
////            additionalValidation = {
////                val groups = it.getClaims(Config.issuers.head.issuer_name)
////                    .get("groups")
////
////                when (groups) {
////                    is List<*> -> groups.contains(Config.jwt.adminGroup) == true
////                    else -> false
////                }
////            }
////        )
// }

    val config = getConfig()
    val issuer = config.issuer

    val jwkProvider = JwkProviderBuilder(URI.create(issuer.jwksurl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    authentication {
        jwt {
            verifier(jwkProvider, issuer.issuer_name) {
                logger.debug { "Verifying jwt" }
                withAudience(issuer.accepted_audience)
            }

            validate { credentials ->
                logger.debug { "Validating jwt" }
                JWTPrincipal(credentials.payload)
            }

            challenge { _, _ ->
                logger.debug { "Jwt is invalid" }
                call.respondError(ApplicationError.Unauthorized())
            }
        }
        jwt("AdminUser") {
            verifier(jwkProvider, issuer.issuer_name) {
                logger.debug { "Verifying admin jwt" }
                withAudience(issuer.accepted_audience)
                withArrayClaim("groups", config.jwt.adminGroup)
            }

            validate { credentials ->
                logger.debug { "Validating admin jwt" }
                JWTPrincipal(credentials.payload)
            }

            challenge { _, _ ->
                logger.debug { "Admin jwt is invalid" }
                call.respondError(ApplicationError.Unauthorized())
            }
        }
        jwt("MachineToken") {
            verifier(jwkProvider, issuer.issuer_name) {
                logger.debug { "Verifying machine jwt" }
                withAudience(issuer.accepted_audience)
                withClaim("idtyp", "app")
            }

            validate { credentials ->
                logger.debug { "Validating machine jwt" }
                val azp = credentials.payload.getClaim("azp")?.asString()
                if (azp == null) {
                    logger.warn { "MachineToken avvist: mangler azp-claim" }
                    return@validate null
                }
                val apps = config.nais.preAuthorizedAppList
                if (config.nais.isRunningOnNais && apps.isEmpty()) { logger.error { "MachineToken avvist: AZURE_APP_PRE_AUTHORIZED_APPS mangler/ugyldig" }; return@validate null }
                if (apps.isNotEmpty() && apps.none { it.clientId == azp }) {
                    logger.warn { "MachineToken avvist: ukjent azp=$azp" }
                    return@validate null
                }
                JWTPrincipal(credentials.payload)
            }

            challenge { _, _ ->
                logger.debug { "Machine jwt is invalid" }
                call.respondError(ApplicationError.Unauthorized())
            }
        }
    }
    install(CORS) {
        allowHost("bob.ansatt.nav.no", schemes = listOf("https"))
        allowHost("bob.ansatt.dev.nav.no", schemes = listOf("https"))

        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
    }
}

fun RoutingContext.isAdmin(): Boolean {
    val config = getConfig()
    val principal = call.principal<JWTPrincipal>()
    val groups = principal?.payload?.claims["groups"]
    return groups?.asList(String::class.java)
        ?.any { it == config.jwt.adminGroup } == true
}
