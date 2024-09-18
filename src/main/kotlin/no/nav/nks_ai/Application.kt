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
import no.nav.nks_ai.citation.CitationRepo
import no.nav.nks_ai.conversation.ConversationRepo
import no.nav.nks_ai.conversation.ConversationService
import no.nav.nks_ai.conversation.conversationRoutes
import no.nav.nks_ai.feedback.FeedbackRepo
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
//            socketTimeout = HTTP_CLIENT_TIMEOUT_MS
//            connectTimeout = HTTP_CLIENT_TIMEOUT_MS
//            connectionRequestTimeout = HTTP_CLIENT_TIMEOUT_MS * 2
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    val entraClient = EntraClient(
        entraTokenUrl = environment.config.property("no.nav.nks_ai.jwt.config_token_endpoint").getString(),
        clientId = environment.config.property("no.nav.nks_ai.jwt.client_id").getString(),
        clientSecret = environment.config.property("no.nav.nks_ai.jwt.client_secret").getString(),
        httpClient = httpClient,
    )

    val kbsClient = KbsClient(
        httpClient = httpClient,
        entraClient = entraClient,
        baseUrl = environment.config.property("no.nav.nks_ai.nks_kbs.url").getString(),
        scope = environment.config.property("no.nav.nks_ai.nks_kbs.scope").getString(),
    )

    val messageRepo = MessageRepo()
    val feedbackRepo = FeedbackRepo()
    val citationRepo = CitationRepo()
    val conversationRepo = ConversationRepo()

    val conversationService = ConversationService(conversationRepo, messageRepo)
    val messageService = MessageService(messageRepo, feedbackRepo, citationRepo)
    val sendMessageService = SendMessageService(conversationService, messageService, kbsClient)

    routing {
        authenticate {
            route("/api/v1") {
                conversationRoutes(conversationService, sendMessageService)
                messageRoutes(messageService)
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

