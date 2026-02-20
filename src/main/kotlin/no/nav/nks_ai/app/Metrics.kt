package no.nav.nks_ai.app

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.core.datapoints.Timer
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import no.nav.nks_ai.core.feedback.ResolvedCategory
import no.nav.nks_ai.core.feedback.ResolvedImportance


internal val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

private const val METRICS_NS = "nksbobapi"

object MetricRegister {
    val conversationsCreated: Counter = Counter.builder()
        .name("${METRICS_NS}_conversations")
        .help("Hvor mange samtaler som er blitt opprettet")
        .register(appMicrometerRegistry.prometheusRegistry)

    val questionsCreated: Counter = Counter.builder()
        .name("${METRICS_NS}_questions")
        .help("Hvor mange spørsmål som er blitt stilt")
        .register(appMicrometerRegistry.prometheusRegistry)

    val answersCreated: Counter = Counter.builder()
        .name("${METRICS_NS}_answers")
        .help("Hvor mange svar som er blitt opprettet")
        .register(appMicrometerRegistry.prometheusRegistry)

    val answersLiked: Counter = Counter.builder()
        .name("${METRICS_NS}_answers_liked")
        .help("Hvor mange svar som har fått tommel opp")
        .register(appMicrometerRegistry.prometheusRegistry)

    val answersDisliked: Counter = Counter.builder()
        .name("${METRICS_NS}_answers_disliked")
        .help("Hvor mange svar som har fått tommel ned")
        .register(appMicrometerRegistry.prometheusRegistry)

    val conversationsLiked: Counter = Counter.builder()
        .name("${METRICS_NS}_conversations_liked")
        .help("Hvor mange samtaler som har fått tommel opp")
        .register(appMicrometerRegistry.prometheusRegistry)

    val conversationsDisliked: Counter = Counter.builder()
        .name("${METRICS_NS}_conversations_disliked")
        .help("Hvor mange samtaler som har fått tommel ned")
        .register(appMicrometerRegistry.prometheusRegistry)

    val sseConnections: Gauge = Gauge.builder()
        .name("${METRICS_NS}_sse_connections")
        .help("Hvor mange aktive SSE-tilkoblinger")
        .register(appMicrometerRegistry.prometheusRegistry)

    val websocketConnections: Gauge = Gauge.builder()
        .name("${METRICS_NS}_websocket_connections")
        .help("Hvor mange aktive websocket-tilkoblinger")
        .register(appMicrometerRegistry.prometheusRegistry)

    val sharedMessageFlows: Gauge = Gauge.builder()
        .name("${METRICS_NS}_shared_message_flows")
        .help("Hvor mange aktive shared message flows")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFirstContentReceivedSummary: Histogram = Histogram.builder()
        .name("${METRICS_NS}_answer_first_content_received")
        .help("Hvor lang tid fra spørsmål er stilt til første del av innholdet i svaret mottas")
        .classicExponentialUpperBounds(0.5, 2.0, 12)
        .register(appMicrometerRegistry.prometheusRegistry)

    fun answerFirstContentReceived(): ManualTimer = ManualTimer(answerFirstContentReceivedSummary)

    private val answerFinishedReceivedSummary: Histogram = Histogram.builder()
        .name("${METRICS_NS}_answer_finished_received")
        .help("Hvor lang tid fra spørsmål er stilt til hele svaret er mottatt")
        .classicExponentialUpperBounds(0.25, 2.0, 12)
        .register(appMicrometerRegistry.prometheusRegistry)

    fun answerFinishedReceived(): ManualTimer = ManualTimer(answerFinishedReceivedSummary)

    val answerFailedReceive: Counter = Counter.builder()
        .name("${METRICS_NS}_answers_failed_receive")
        .help("Hvor mange svar som har feilet underveis når de mottas fra KBS")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbacks: Counter = Counter.builder()
        .name("${METRICS_NS}_answer_feedbacks")
        .help("Hvor mange tilbakemeldinger som har kommet på svar")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackOptions: Counter = Counter.builder()
        .name("${METRICS_NS}_answer_feedback_options")
        .help("Totalt antall valg på tilbakemeldinger")
        .labelNames("valg")
        .withExemplars()
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackComments: Counter = Counter.builder()
        .name("${METRICS_NS}_answer_feedback_comments")
        .help("Totalt antall kommentarer på tilbakemeldinger")
        .register(appMicrometerRegistry.prometheusRegistry)

    fun trackFeedback(options: List<String>, hasComment: Boolean) {
        answerFeedbacks.inc()
        if (options.isNotEmpty()) {
            answerFeedbackOptions.labelValues(*options.toTypedArray()).inc()
        }
        if (hasComment) {
            answerFeedbackComments.inc()
        }
    }

    private val answerFeedbacksResolved: Counter = Counter.builder()
        .name("${METRICS_NS}_answer_feedbacks_resolved")
        .help("Hvor mange tilbakemeldinger som har blitt ferdigstilt")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackResolvedImportance: Counter = Counter.builder()
        .name("${METRICS_NS}_answer_feedback_resolved_importance")
        .help("Totalt antall viktighet på tilbakemeldinger")
        .labelNames("viktighet")
        .register(appMicrometerRegistry.prometheusRegistry)

    private val answerFeedbackResolvedCategory: Counter = Counter.builder()
        .name("${METRICS_NS}_answer_feedback_resolved_category")
        .help("Totalt antall valg på tilbakemeldinger")
        .labelNames("kategori")
        .register(appMicrometerRegistry.prometheusRegistry)

    fun trackFeedbackResolved(
        resolved: Boolean,
        resolvedImportance: ResolvedImportance?,
        resolvedCategory: ResolvedCategory?
    ) {
        if (resolved) {
            answerFeedbacksResolved.inc()
            resolvedImportance?.let {
                answerFeedbackResolvedImportance.labelValues(it.value).inc()
            }
            resolvedCategory?.let {
                answerFeedbackResolvedCategory.labelValues(it.value).inc()
            }
        }
    }
}

class ManualTimer {
    private val histogram: Histogram
    private val timer: Timer
    var isRunning: Boolean
        private set

    constructor(histogram: Histogram) {
        this.histogram = histogram
        this.isRunning = true
        this.timer = histogram.startTimer()
    }

    fun stop() {
        if (isRunning) {
            timer.observeDuration()
            isRunning = false
        }
    }
}
