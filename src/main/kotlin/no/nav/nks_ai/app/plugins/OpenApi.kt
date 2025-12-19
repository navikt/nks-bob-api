package no.nav.nks_ai.app.plugins

import io.github.smiley4.ktoropenapi.OpenApi
import io.ktor.server.application.Application
import io.ktor.server.application.install

fun Application.configureOpenApi() {
    install(OpenApi) {
        info {
            title = "NKS Bob API"
            description = "API for Nav Kontaktsenters chatbot Bob."
        }
        schemas {
//            generator = SchemaGenerator.kotlinx()
        }
        autoDocumentResourcesRoutes = true
    }
}
