package no.nav.nks_ai.plugins

import com.codahale.metrics.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.metrics.dropwizard.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.*
import java.util.concurrent.TimeUnit

fun Application.configureMonitoring() {
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        // ...
    }
    install(DropwizardMetrics) {
        Slf4jReporter.forRegistry(registry)
//            .outputTo(this@configureMonitoring.log)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(10, TimeUnit.MINUTES) // TODO
    }
//    install(CallLogging) {
//        level = Level.INFO
//        filter { call -> call.request.path().startsWith("/") }
//        callIdMdc("call-id")
//    }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { callId: String ->
            callId.isNotEmpty()
        }
    }
    routing {
        get("/internal/prometheus") {
            call.respond(appMicrometerRegistry.scrape())
        }
    }
}

fun Route.healthRoutes() {
    get("/is_alive") {
        call.respondText("alive", ContentType.Text.Plain, HttpStatusCode.OK)
    }
    get("/is_ready") {
        call.respondText("ready", ContentType.Text.Plain, HttpStatusCode.OK)
    }
}
