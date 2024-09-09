package no.nav.nks_ai.plugins

import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.webjars.Webjars

fun Application.configureSwagger() {
    install(Webjars) {
        path = "/webjars" //defaults to /webjars
    }
    install(SwaggerUI) {
        swagger {
            swaggerUrl = "swagger-ui"
            forwardRoot = true
        }
        info {
            title = "Example API"
            version = "latest"
            description = "Example API for testing and demonstration purposes."
        }
        server {
            url = "http://localhost:8080"
            description = "Development Server"
        }
    }
//    routing {
//        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
//        swaggerUI(path = "swagger-ui", swaggerFile = "openapi/documentation.yaml")
//    }
}
