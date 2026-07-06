package no.nav.nks_ai.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.*
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.auth.authenticate
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import no.nav.nks_ai.api.app.Config
import no.nav.nks_ai.api.app.FeatureToggles
import no.nav.nks_ai.api.app.MetricRegister
import no.nav.nks_ai.api.app.bq.BigQueryClient
import no.nav.nks_ai.api.app.bq.getBigQueryClient
import no.nav.nks_ai.api.app.getConfig
import no.nav.nks_ai.api.app.plugins.configureDatabases
import no.nav.nks_ai.api.app.plugins.configureMonitoring
import no.nav.nks_ai.api.app.plugins.configureSecurity
import no.nav.nks_ai.api.app.plugins.configureSerialization
import no.nav.nks_ai.api.app.plugins.healthRoutes
import no.nav.nks_ai.api.core.MarkMessageStarredService
import no.nav.nks_ai.api.core.SendMessageService
import no.nav.nks_ai.api.core.admin.AdminService
import no.nav.nks_ai.api.core.admin.adminRoutes
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.conversation.conversationRoutes
import no.nav.nks_ai.api.core.conversation.streaming.conversationSse
import no.nav.nks_ai.api.core.conversation.streaming.conversationWebsocket
import no.nav.nks_ai.api.core.feedback.feedbackAdminBatchRoutes
import no.nav.nks_ai.api.core.feedback.feedbackAdminRoutes
import no.nav.nks_ai.api.core.feedback.feedbackService
import no.nav.nks_ai.api.core.ignoredWords.ignoredWordsAdminRoutes
import no.nav.nks_ai.api.core.ignoredWords.ignoredWordsRoutes
import no.nav.nks_ai.api.core.ignoredWords.ignoredWordsService
import no.nav.nks_ai.api.core.jobs.jobService
import no.nav.nks_ai.api.core.jobs.jobsRoutes
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.messageRoutes
import no.nav.nks_ai.api.core.notification.notificationAdminRoutes
import no.nav.nks_ai.api.core.notification.notificationService
import no.nav.nks_ai.api.core.notification.notificationUserRoutes
import no.nav.nks_ai.api.core.user.UserConfigService
import no.nav.nks_ai.api.core.user.userConfigRoutes
import no.nav.nks_ai.api.kbs.KbsClient
import no.nav.nks_ai.api.vaskemaskin.VaskemaskinClient
import no.nav.nks_ai.api.v2.core.conversation.streaming.conversationSseV2
import no.nav.nks_ai.shared.auth.TexasClient

fun main(args: Array<String>) {
    EngineMain.main(args)
}

private val logger = KotlinLogging.logger { }

fun Application.module() {
    configureSerialization()
    configureDatabases()
    configureMonitoring()
    configureSecurity()

    val config = getConfig()

    val httpClient = defaultHttpClient()

    val sseClient = sseHttpClient()

    val texasClient = TexasClient(
        naisTokenEndpoint = config.nais.tokenEndpoint,
        httpClient = httpClient,
        logger = logger
    )

    val kbsClient = KbsClient(
        sseClient = sseClient,
        texasClient = texasClient,
        baseUrl = config.kbs.url,
        scope = config.kbs.scope,
    )

    val kbsClientV2 = no.nav.nks_ai.api.v2.kbs.KbsClient(
        sseClient = sseClient,
        texasClient = texasClient,
        baseUrl = config.kbs.url,
        scope = config.kbs.scope,
    )

    val bigQueryClient = getBigQueryClient()

    val vaskemaskinClient = VaskemaskinClient(
        baseUrl = config.vaskemaskin.url,
        httpClient = httpClient,
        texasClient = texasClient,
        targetAudience = config.vaskemaskin.scope,
    )

    val featureToggles = FeatureToggles.create(config.unleash)

    monitor.subscribe(ApplicationStopping) {
        logger.info {
            "Graceful shutdown initiated. Active SSE connections: ${MetricRegister.sseConnections.get()}"
        }
    }

    monitor.subscribe(ApplicationStopped) {
        httpClient.close()
        sseClient.close()
    }

    val conversationService = ConversationService()
    val messageService = MessageService(vaskemaskinClient, featureToggles, this)
    val sendMessageService = SendMessageService(conversationService, messageService, kbsClient)
    val sendMessageServiceV2 =
        no.nav.nks_ai.api.v2.core.SendMessageService(conversationService, messageService, kbsClientV2)
    val adminService = AdminService()
    val userConfigService = UserConfigService()
    val markMessageStarredService = MarkMessageStarredService(bigQueryClient, messageService)
    val notificationService = notificationService()
    val feedbackService = feedbackService(messageService)
    val ignoredWordsService = ignoredWordsService()
    val jobService = jobService(messageService, conversationService, markMessageStarredService, ignoredWordsService)

    routing {
        route("/api/v1") {
            authenticate {
                conversationRoutes(conversationService, messageService, sendMessageService)
                conversationWebsocket(conversationService, messageService, sendMessageService)
                conversationSse(messageService, sendMessageService)
                userConfigRoutes(userConfigService)
                messageRoutes(messageService, feedbackService)
                notificationUserRoutes(notificationService)
                ignoredWordsRoutes(ignoredWordsService)
            }
            authenticate("AdminUser") {
                adminRoutes(adminService)
                notificationAdminRoutes(notificationService)
                feedbackAdminRoutes(feedbackService)
                feedbackAdminBatchRoutes(feedbackService)
                ignoredWordsAdminRoutes(ignoredWordsService)
            }
            authenticate("MachineToken") {
                jobsRoutes(jobService)
            }
        }
        route("/api/v2") {
            authenticate {
                conversationSseV2(messageService, sendMessageServiceV2)
            }
        }
        route("/internal") {
            healthRoutes()
        }
        if (config.nais.isRunningOnNais) {
            authenticate("AdminUser") {
                swaggerUI("/swagger-ui") {
                    info = OpenApiInfo(
                        version = "0.0.1",
                        title = "NKS Bob API",
                        description = "API for Nav Kontaktsenters chatbot Bob."
                    )
                    source = OpenApiDocSource.Routing {
                        routingRoot.descendants()
                    }
                }
            }
        } else {
            swaggerUI("/swagger-ui") {
                info = OpenApiInfo(
                    version = "0.0.1",
                    title = "NKS Bob API",
                    description = "API for Nav Kontaktsenters chatbot Bob."
                )
                source = OpenApiDocSource.Routing {
                    routingRoot.descendants()
                }
            }
        }
    }
}

fun defaultJsonConfig(
    block: JsonBuilder.() -> Unit = {}
): Json = Json {
    ignoreUnknownKeys = true
    block()
}

// Short-lived client for token fetching and API calls (Entra, Vaskemaskin, Texas).
// Uses aggressive timeouts to fail fast — a 10-minute hang here blocks Dispatchers.IO threads
// and cascades into total stoppage.
private fun defaultHttpClient(
    block: HttpClientConfig<CIOEngineConfig>.() -> Unit = {}
): HttpClient =
    HttpClient(CIO) {
        engine {
            // Disable CIO's own request timeout — let HttpTimeout plugin manage all timeouts.
            // In Ktor 3.5.0 the CIO engine's internal requestTimeout can silently abort requests
            // without throwing an exception, overriding the plugin's behavior.
            requestTimeout = 0

            endpoint {
                // Recycle idle connections after 55s. GCP load balancers close idle TCP connections
                // after 600s by default, but intermediate Kubernetes/Envoy proxies may close earlier.
                // Without this, CIO reuses dead connections and hangs silently.
                keepAliveTime = 55_000
                connectTimeout = 10_000
                socketTimeout = 30_000
            }
        }
        install(HttpTimeout) {
            socketTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(defaultJsonConfig())
        }
        install(CallId) {
            intercept { request, callId ->
                request.header(HttpHeaders.XRequestId, callId)
            }
        }
        block()
    }

// Long-lived client for SSE streaming to KBS — AI responses can take several minutes.
// Must NOT share timeout settings with defaultHttpClient.
private fun sseHttpClient(): HttpClient =
    HttpClient(CIO) {
        engine {
            requestTimeout = 0
            endpoint {
                keepAliveTime = 55_000
                connectTimeout = 10_000
                socketTimeout = Config.HTTP_CLIENT_TIMEOUT_MS.toLong()
            }
        }
        install(SSE)
        install(HttpTimeout) {
            socketTimeoutMillis = Config.HTTP_CLIENT_TIMEOUT_MS.toLong()
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = Config.HTTP_CLIENT_TIMEOUT_MS.toLong() * 2
        }
        install(ContentNegotiation) {
            json(defaultJsonConfig())
        }
        install(CallId) {
            // TODO currently not supporting sse-client.
            intercept { request, callId ->
                request.header(HttpHeaders.XRequestId, callId)
            }
        }
    }