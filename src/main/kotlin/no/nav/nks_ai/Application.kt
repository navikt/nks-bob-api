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
import no.nav.nks_ai.conversation.ConversationRepo
import no.nav.nks_ai.conversation.ConversationService
import no.nav.nks_ai.conversation.conversationRoutes
import no.nav.nks_ai.feedback.FeedbackRepo
import no.nav.nks_ai.kbs.KbsClient
import no.nav.nks_ai.message.Message
import no.nav.nks_ai.message.MessageRepo
import no.nav.nks_ai.message.MessageService
import no.nav.nks_ai.message.NewMessage
import no.nav.nks_ai.message.messageRoutes
import no.nav.nks_ai.plugins.configureCache
import no.nav.nks_ai.plugins.configureDatabases
import no.nav.nks_ai.plugins.configureMonitoring
import no.nav.nks_ai.plugins.configureSecurity
import no.nav.nks_ai.plugins.configureSerialization
import no.nav.nks_ai.plugins.configureSwagger
import no.nav.nks_ai.plugins.healthRoutes
import java.util.UUID

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
            json()
        }
    }

    val kbsClient = KbsClient("https://nks-kbs.ansatt.dev.nav.no", httpClient)

    val messageRepo = MessageRepo()
    val feedbackRepo = FeedbackRepo()
    val conversationRepo = ConversationRepo()

    val conversationService = ConversationService(conversationRepo, messageRepo)
    val messageService = MessageService(messageRepo, feedbackRepo)
    val sendMessageService = SendMessageService(conversationService, messageService, kbsClient)

    routing {
        authenticate {
            route("/api/v1") {
                conversationRoutes(conversationService, messageService, sendMessageService)
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

class SendMessageService(
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val kbsClient: KbsClient
) {
    suspend fun sendMessage(
        message: NewMessage,
        conversationId: UUID,
        navIdent: String,
        token: String
    ): Message? {
        val history = conversationService.getConversationMessages(conversationId, navIdent).map { it.content }
        messageService.addMessage(message, conversationId)

        val answer = kbsClient.sendQuestion(
            question = message.content,
            messageHistory = history,
            token = token,
        )

        val answerContent = answer?.answer?.text
        if (answerContent == null) {
            // TODO error
            return null
        }

        val newMessage = messageService.addMessage(NewMessage(answerContent), conversationId)
        return newMessage
    }
}
