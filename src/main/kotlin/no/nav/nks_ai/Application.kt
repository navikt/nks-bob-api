package no.nav.nks_ai

import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import no.nav.nks_ai.auth.EntraClient
import no.nav.nks_ai.conversation.ConversationRepo
import no.nav.nks_ai.conversation.ConversationService
import no.nav.nks_ai.conversation.conversationRoutes
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.message.MessageRepo
import no.nav.nks_ai.message.MessageService
import no.nav.nks_ai.message.messageRoutes
import no.nav.nks_ai.plugins.configureCache
import no.nav.nks_ai.plugins.configureDatabases
import no.nav.nks_ai.plugins.configureMonitoring
import no.nav.nks_ai.plugins.configureSecurity
import no.nav.nks_ai.plugins.configureSerialization
import no.nav.nks_ai.plugins.configureSwagger
import no.nav.nks_ai.plugins.healthRoutes
import no.nav.nks_ai.user.UserConfigRepo
import no.nav.nks_ai.user.UserConfigService
import no.nav.nks_ai.user.userConfigRoutes

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

    val entraClient = EntraClient(
        entraTokenUrl = Config.jwt.configTokenEndpoint,
        clientId = Config.jwt.clientId,
        clientSecret = Config.jwt.clientSecret,
        httpClient = httpClient,
    )

    val kbsClient = KbsClient(
        httpClient = httpClient,
        entraClient = entraClient,
        baseUrl = Config.kbs.url,
        scope = Config.kbs.scope,
    )

    val messageRepo = MessageRepo()
    val conversationRepo = ConversationRepo()
    val userConfigRepo = UserConfigRepo()

    val conversationService = ConversationService(conversationRepo, messageRepo)
    val messageService = MessageService(messageRepo)
    val sendMessageService = SendMessageService(conversationService, messageService, kbsClient)
    val adminService = AdminService(conversationRepo)
    val userConfigService = UserConfigService(userConfigRepo)

    routing {
        route("/api/v1") {
            authenticate {
                conversationRoutes(conversationService, sendMessageService)
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