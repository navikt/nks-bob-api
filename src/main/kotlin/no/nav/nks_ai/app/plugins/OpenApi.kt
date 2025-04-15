package no.nav.nks_ai.app.plugins

import com.ucasoft.ktor.simpleCache.CacheOutputSelector
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.ktor.server.application.Application
import io.ktor.server.application.install

fun Application.configureOpenApi() {
    install(OpenApi) {
        info {
            title = "NKS Bob API"
            description = "API for Nav Kontaktsenters chatbot Bob."
        }
        schemas {
            generator = SchemaGenerator.kotlinx()
        }
        autoDocumentResourcesRoutes = true
        ignoredRouteSelectors = ignoredRouteSelectors + CacheOutputSelector::class
    }
}
