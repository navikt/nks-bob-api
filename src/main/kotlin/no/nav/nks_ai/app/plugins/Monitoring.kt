package no.nav.nks_ai.app.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import no.nav.nks_ai.app.appMicrometerRegistry
import org.slf4j.event.Level

val defautOpenTelemetry: OpenTelemetry = AutoConfiguredOpenTelemetrySdk.builder()
    .build()
    .openTelemetrySdk

fun Application.configureMonitoring() {
    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
    }
//    install(DropwizardMetrics) {
//        Slf4jReporter.forRegistry(registry)
////            .outputTo(this@configureMonitoring.log)
//            .convertRatesTo(TimeUnit.SECONDS)
//            .convertDurationsTo(TimeUnit.MILLISECONDS)
//            .build()
//            .start(10, TimeUnit.MINUTES) // TODO
//    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api/v1") }
        callIdMdc("call-id")
    }
    install(CallId) {
        header("nav-call-id")
        replyToHeader(HttpHeaders.XRequestId)
        verify { callId: String ->
            callId.isNotEmpty()
        }
    }
    install(KtorServerTelemetry) {
        setOpenTelemetry(defautOpenTelemetry)
    }
}

fun Route.healthRoutes() {
    get("/is_alive") {
        call.respondText("alive", ContentType.Text.Plain, HttpStatusCode.OK)
    }
    get("/is_ready") {
        call.respondText("ready", ContentType.Text.Plain, HttpStatusCode.OK)
    }
    get("/prometheus") {
        call.respond(appMicrometerRegistry.scrape())
    }
}
