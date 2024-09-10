package no.nav.nks_ai.plugins

import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.ktor.server.application.Application
import io.ktor.server.application.install

fun Application.configureSwagger() {
    install(SwaggerUI) {
        swagger {
        }
        info {
            title = "NKS Bob API"
            description = "API for Nav Kontaktsenters chatbot Bob"
        }
    }
}
