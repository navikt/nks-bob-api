package no.nav.nks_ai.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.apache.ApacheEngineConfig
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
import no.nav.nks_ai.api.app.bq.BigQueryClient
import no.nav.nks_ai.api.app.plugins.configureDatabases
import no.nav.nks_ai.api.app.plugins.configureMonitoring
import no.nav.nks_ai.api.app.plugins.configureSecurity
import no.nav.nks_ai.api.app.plugins.configureSerialization
import no.nav.nks_ai.api.app.plugins.healthRoutes
import no.nav.nks_ai.api.core.ConversationDeletionJob
import no.nav.nks_ai.api.core.MarkMessageStarredService
import no.nav.nks_ai.api.core.SendMessageService
import no.nav.nks_ai.api.core.UploadStarredMessagesJob
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
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.messageRoutes
import no.nav.nks_ai.api.core.notification.notificationAdminRoutes
import no.nav.nks_ai.api.core.notification.notificationService
import no.nav.nks_ai.api.core.notification.notificationUserRoutes
import no.nav.nks_ai.api.core.user.UserConfigService
import no.nav.nks_ai.api.core.user.userConfigRoutes
import no.nav.nks_ai.api.kbs.KbsClient
import no.nav.nks_ai.shared.auth.EntraClient

fun main(args: Array<String>) {
    EngineMain.main(args)
}

val logger = KotlinLogging.logger { }

fun Application.module() {
    configureSerialization()
    configureDatabases()
    configureMonitoring()
    configureSecurity()

    val httpClient = defaultHttpClient {}

    val sseClient = defaultHttpClient {
        install(SSE)
    }

    val entraClient = EntraClient(
        entraTokenUrl = Config.jwt.configTokenEndpoint,
        clientId = Config.jwt.clientId,
        clientSecret = Config.jwt.clientSecret,
        httpClient = httpClient,
        logger = logger,
    )

    val kbsClient = KbsClient(
        sseClient = sseClient,
        entraClient = entraClient,
        baseUrl = Config.kbs.url,
        scope = Config.kbs.scope,
    )

    val bigQueryClient = BigQueryClient()

    val conversationService = ConversationService()
    val messageService = MessageService()
    val sendMessageService = SendMessageService(conversationService, messageService, kbsClient)
    val adminService = AdminService()
    val userConfigService = UserConfigService()
    val markMessageStarredService = MarkMessageStarredService(bigQueryClient, messageService)
    val notificationService = notificationService()
    val feedbackService = feedbackService(messageService)
    val ignoredWordsService = ignoredWordsService()

    ConversationDeletionJob(conversationService, messageService, httpClient).start()
    UploadStarredMessagesJob(messageService, markMessageStarredService, httpClient).start()

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
        }
        route("/internal") {
            healthRoutes()
        }
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

fun defaultJsonConfig(
    block: JsonBuilder.() -> Unit = {}
): Json = Json {
    ignoreUnknownKeys = true
    block()
}

private fun defaultHttpClient(
    block: HttpClientConfig<ApacheEngineConfig>.() -> Unit = {}
): HttpClient =
    HttpClient(Apache) {
        engine {
            socketTimeout = Config.HTTP_CLIENT_TIMEOUT_MS
            connectTimeout = Config.HTTP_CLIENT_TIMEOUT_MS
            connectionRequestTimeout = Config.HTTP_CLIENT_TIMEOUT_MS * 2
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
        block()
    }