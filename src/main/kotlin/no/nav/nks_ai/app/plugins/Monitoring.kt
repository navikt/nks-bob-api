package no.nav.nks_ai.app.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.*
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.SimpleTimer
import io.prometheus.client.Summary
import org.slf4j.event.Level

private val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

private const val METRICS_NS = "nksbobapi"

object MetricRegister {
    val conversationsCreated = Counter.Builder()
        .name("${METRICS_NS}_conversations_created")
        .help("Hvor mange samtaler som er blitt opprettet")
        .register(appMicrometerRegistry.prometheusRegistry)

    val questionsCreated = Counter.Builder()
        .name("${METRICS_NS}_questions_created")
        .help("Hvor mange spørsmål som er blitt stilt")
        .register(appMicrometerRegistry.prometheusRegistry)

    val answersCreated = Counter.Builder()
        .name("${METRICS_NS}_answers_created")
        .help("Hvor mange svar som er blitt opprettet")
        .register(appMicrometerRegistry.prometheusRegistry)

    val answersLiked = Counter.Builder()
        .name("${METRICS_NS}_answers_liked")
        .help("Hvor mange svar som har fått tommel opp")
        .register(appMicrometerRegistry.prometheusRegistry)

    val answersDisliked = Counter.Builder()
        .name("${METRICS_NS}_answers_disliked")
        .help("Hvor mange svar som har fått tommel ned")
        .register(appMicrometerRegistry.prometheusRegistry)

    val sseConnections = Gauge.Builder()
        .name("${METRICS_NS}_sse_connections")
        .help("Hvor mange aktive SSE-tilkoblinger")
        .register(appMicrometerRegistry.prometheusRegistry)

    val websocketConnections = Gauge.Builder()
        .name("${METRICS_NS}_websocket_connections")
        .help("Hvor mange aktive websocket-tilkoblinger")
        .register(appMicrometerRegistry.prometheusRegistry)

    val sharedMessageFlows = Gauge.Builder()
        .name("${METRICS_NS}_shared_message_flows")
        .help("Hvor mange aktive shared message flows")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFirstContentReceivedSummary = Summary.Builder()
        .name("${METRICS_NS}_answer_first_content_received")
        .help("Hvor lang tid fra spørsmål er stilt til første del av innholdet i svaret mottas")
        .register(appMicrometerRegistry.prometheusRegistry)

    fun answerFirstContentReceived(): Timer = Timer(answerFirstContentReceivedSummary)
}

class Timer {
    private val summary: Summary
    private val timer: SimpleTimer
    var isRunning: Boolean
        private set

    constructor(summary: Summary) {
        this.summary = summary
        this.timer = SimpleTimer()
        this.isRunning = true
    }

    fun stop() {
        summary.startTimer()
        if (isRunning) {
            summary.observe(timer.elapsedSeconds())
            isRunning = false
        }
    }
}

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
