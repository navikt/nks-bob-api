package no.nav.nks_ai.app.plugins

import com.auth0.jwk.JwkProviderBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.cors.routing.CORS
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.respondError
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

    val jwkProvider = JwkProviderBuilder(URI.create(Config.issuers.head.jwksurl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    authentication {
        jwt {
            verifier(jwkProvider, Config.issuers.head.issuer_name) {
                logger.debug { "Verifying jwt" }
                withAudience(Config.issuers.head.accepted_audience)
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
            verifier(jwkProvider, Config.issuers.head.issuer_name) {
                logger.debug { "Verifying admin jwt" }
                withAudience(Config.issuers.head.accepted_audience)
                withArrayClaim("groups", Config.jwt.adminGroup)
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
    }
    install(CORS) {
        anyHost()

        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }
}
