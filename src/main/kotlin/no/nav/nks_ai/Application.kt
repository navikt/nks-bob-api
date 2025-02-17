package no.nav.nks_ai

import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.apache.ApacheEngineConfig
import io.ktor.client.plugins.callid.CallId
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.bq.BigQueryClient
import no.nav.nks_ai.app.plugins.configureCache
import no.nav.nks_ai.app.plugins.configureDatabases
import no.nav.nks_ai.app.plugins.configureMonitoring
import no.nav.nks_ai.app.plugins.configureSecurity
import no.nav.nks_ai.app.plugins.configureSerialization
import no.nav.nks_ai.app.plugins.configureSwagger
import no.nav.nks_ai.app.plugins.healthRoutes
import no.nav.nks_ai.auth.EntraClient
import no.nav.nks_ai.core.ConversationDeletionJob
import no.nav.nks_ai.core.HighlightMessageService
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.admin.AdminService
import no.nav.nks_ai.core.admin.adminRoutes
import no.nav.nks_ai.core.article.ArticleService
import no.nav.nks_ai.core.article.articleRoutes
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.conversation.conversationRoutes
import no.nav.nks_ai.core.conversation.conversationSse
import no.nav.nks_ai.core.conversation.streaming.conversationWebsocket
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.messageRoutes
import no.nav.nks_ai.core.user.UserConfigService
import no.nav.nks_ai.core.user.userConfigRoutes
import no.nav.nks_ai.kbs.KbsClient

fun main(args: Array<String>) {
    EngineMain.main(args)
}

@Suppress("unused")
fun Application.module() {
    configureSerialization()
    configureDatabases()
    configureMonitoring()
    configureCache()
    configureSecurity()
    configureSwagger()

    val httpClient = defaultHttpClient {}

    val sseClient = defaultHttpClient {
        install(SSE)
    }

    val entraClient = EntraClient(
        entraTokenUrl = Config.jwt.configTokenEndpoint,
        clientId = Config.jwt.clientId,
        clientSecret = Config.jwt.clientSecret,
        httpClient = httpClient,
    )

    val kbsClient = KbsClient(
        httpClient = httpClient,
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
    val articleService = ArticleService(bigQueryClient)
    val highlightMessageService = HighlightMessageService(bigQueryClient, messageService)

    ConversationDeletionJob(conversationService, httpClient).start()

    routing {
        route("/api/v1") {
            authenticate {
                conversationRoutes(conversationService, sendMessageService)
                conversationWebsocket(conversationService, sendMessageService)
                conversationSse(conversationService)
                userConfigRoutes(userConfigService)
                messageRoutes(messageService, highlightMessageService)
                articleRoutes(articleService)
            }
            authenticate("AdminUser") {
                adminRoutes(adminService)
            }
        }
        route("/internal") {
            healthRoutes()
        }
        route("/swagger-ui") {
            swaggerUI("/swagger-ui/api.json")
            route("/api.json") {
                openApiSpec()
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