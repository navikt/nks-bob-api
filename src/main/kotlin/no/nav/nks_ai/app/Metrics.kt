package no.nav.nks_ai.app

import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.SimpleTimer
import io.prometheus.client.Summary
import no.nav.nks_ai.core.feedback.ResolvedCategory


internal val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

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

    val conversationsLiked = Counter.Builder()
        .name("${METRICS_NS}_conversations_liked")
        .help("Hvor mange samtaler som har fått tommel opp")
        .register(appMicrometerRegistry.prometheusRegistry)

    val conversationsDisliked = Counter.Builder()
        .name("${METRICS_NS}_conversations_disliked")
        .help("Hvor mange samtaler som har fått tommel ned")
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

    private val answerFinishedReceivedSummary = Summary.Builder()
        .name("${METRICS_NS}_answer_finished_received")
        .help("Hvor lang tid fra spørsmål er stilt til hele svaret er mottatt")
        .register(appMicrometerRegistry.prometheusRegistry)

    fun answerFinishedReceived(): Timer = Timer(answerFinishedReceivedSummary)

    val answerFailedReceive = Counter.Builder()
        .name("${METRICS_NS}_answers_failed_receive")
        .help("Hvor mange svar som har feilet underveis når de mottas fra KBS")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbacks = Counter.Builder()
        .name("${METRICS_NS}_answer_feedbacks")
        .help("Hvor mange tilbakemeldinger som har kommet på svar")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackOptions = Counter.Builder()
        .name("${METRICS_NS}_answer_feedback_options")
        .help("Totalt antall valg på tilbakemeldinger")
        .labelNames("valg")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackComments = Counter.Builder()
        .name("${METRICS_NS}_answer_feedback_comments")
        .help("Totalt antall kommentarer på tilbakemeldinger")
        .register(appMicrometerRegistry.prometheusRegistry)

    fun trackFeedback(options: List<String>, hasComment: Boolean) {
        answerFeedbacks.inc()
        options.forEach { option ->
            answerFeedbackOptions.labels(option).inc()
        }
        if (hasComment) {
            answerFeedbackComments.inc()
        }
    }

    private val answerFeedbacksResolved = Counter.Builder()
        .name("${METRICS_NS}_answer_feedbacks_resolved")
        .help("Hvor mange tilbakemeldinger som har blitt ferdigstilt")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackResolvedCategory = Counter.Builder()
        .name("${METRICS_NS}_answer_feedback_resolvedCategory")
        .help("Totalt antall valg på tilbakemeldinger")
        .labelNames("kategori")
        .register(appMicrometerRegistry.prometheusRegistry)

    fun trackFeedbackResolved(resolvedCategory: ResolvedCategory?) {
        answerFeedbacksResolved.inc()
        resolvedCategory?.let {
            answerFeedbackResolvedCategory.labels(it.value).inc()
        }
    }
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

        summary.startTimer()
    }

    fun stop() {
        if (isRunning) {
            summary.observe(timer.elapsedSeconds())
            isRunning = false
        }
    }
}
