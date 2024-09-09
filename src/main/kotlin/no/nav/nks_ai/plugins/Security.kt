package no.nav.nks_ai.plugins

//import com.auth0.jwt.JWT
//import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.util.DefaultResourceRetriever
import io.ktor.server.application.*
import io.ktor.server.auth.*
//import io.ktor.server.auth.jwt.*
import no.nav.security.token.support.v2.tokenValidationSupport

fun Application.configureSecurity() {
    // Please read the jwt property from the config file if you are using EngineMain
//    val jwtAudience = "jwt-audience"
//    val jwtDomain = "https://jwt-provider-domain/"
//    val jwtRealm = "ktor sample app"
//    val jwtSecret = "secret"

    install(Authentication) {
        tokenValidationSupport(
            config = this@configureSecurity.environment.config,
            resourceRetriever = DefaultResourceRetriever()
        )
    }

//    authentication {
//        jwt {
//            realm = jwtRealm
//            verifier(
//                JWT
//                    .require(Algorithm.HMAC256(jwtSecret))
//                    .withAudience(jwtAudience)
//                    .withIssuer(jwtDomain)
//                    .build()
//            )
//            validate { credential ->
//                if (credential.payload.audience.contains(jwtAudience)) JWTPrincipal(credential.payload) else null
//            }
//        }
//    }
}

//private fun ApplicationCall.getClaim(issuer: String, name: String) =
//    authentication.principal<TokenValidationContextPrincipal>()
//        ?.context
//        ?.getClaims(issuer)
//        ?.getStringClaim(name)