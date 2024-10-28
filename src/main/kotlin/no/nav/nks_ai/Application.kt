package no.nav.nks_ai

import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.plugins.configureCache
import no.nav.nks_ai.app.plugins.configureDatabases
import no.nav.nks_ai.app.plugins.configureMonitoring
import no.nav.nks_ai.app.plugins.configureSecurity
import no.nav.nks_ai.app.plugins.configureSerialization
import no.nav.nks_ai.app.plugins.configureSwagger
import no.nav.nks_ai.app.plugins.healthRoutes
import no.nav.nks_ai.auth.EntraClient
import no.nav.nks_ai.core.SendMessageService
import no.nav.nks_ai.core.admin.AdminService
import no.nav.nks_ai.core.admin.adminRoutes
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.conversation.conversationRoutes
import no.nav.nks_ai.core.conversation.conversationSse
import no.nav.nks_ai.core.conversation.conversationWebsocket
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

    val httpClient = HttpClient(Apache) {
        engine {
            socketTimeout = Config.HTTP_CLIENT_TIMEOUT_MS
            connectTimeout = Config.HTTP_CLIENT_TIMEOUT_MS
            connectionRequestTimeout = Config.HTTP_CLIENT_TIMEOUT_MS * 2
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    val sseClient = HttpClient(Apache) {
        install(SSE)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
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

    val conversationService = ConversationService()
    val messageService = MessageService()
    val sendMessageService = SendMessageService(conversationService, messageService, kbsClient)
    val adminService = AdminService()
    val userConfigService = UserConfigService()

    routing {
        route("/api/v1") {
            authenticate {
                conversationRoutes(conversationService, sendMessageService)
                conversationWebsocket(conversationService, sendMessageService)
                conversationSse(conversationService)
                messageRoutes(messageService)
                userConfigRoutes(userConfigService)
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