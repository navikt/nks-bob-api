package no.nav.nks_ai.integration

import io.github.smiley4.ktoropenapi.openApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import no.nav.nks_ai.app.Config
import no.nav.nks_ai.app.plugins.configureSerialization
import no.nav.nks_ai.core.conversation.ConversationService
import no.nav.nks_ai.core.feedback.feedbackAdminRoutes
import no.nav.nks_ai.core.feedback.feedbackService
import no.nav.nks_ai.core.message.MessageService
import no.nav.nks_ai.core.message.messageRoutes
import no.nav.nks_ai.core.notification.notificationAdminRoutes
import no.nav.nks_ai.core.notification.notificationService
import no.nav.nks_ai.core.notification.notificationUserRoutes
import no.nav.nks_ai.core.user.UserConfigService
import no.nav.nks_ai.core.user.userConfigRoutes
import no.nav.nks_ai.app.plugins.configureOpenApi

/**
 * Base class for API integration tests.
 * Sets up a full Ktor application with test authentication and database.
 */
abstract class ApiIntegrationTestBase : IntegrationTestBase() {

    protected val testNavIdent = "Z999999"
    protected val testAdminNavIdent = "Z000000"

    /**
     * Configure the test application with all plugins and routes.
     * Note: Database is already configured by IntegrationTestBase, so we skip configureDatabases()
     */
    protected fun Application.testModule() {
        // Ensure database is set up before application starts
        setupDatabase()

        configureSerialization()
        configureOpenApi()
        // Skip configureDatabases() - already set up by IntegrationTestBase with Testcontainers
        configureTestAuthentication()

        // Initialize services
        val conversationService = ConversationService()
        val messageService = MessageService()
        val userConfigService = UserConfigService()
        val notificationService = notificationService()
        val feedbackService = feedbackService(messageService)

        routing {
            route("/api/v1") {
                authenticate("test-auth") {
                    userConfigRoutes(userConfigService)
                    messageRoutes(messageService, feedbackService)
                    notificationUserRoutes(notificationService)
                }
                authenticate("test-auth-admin") {
                    notificationAdminRoutes(notificationService)
                    feedbackAdminRoutes(feedbackService)
                }
            }
            // OpenAPI spec endpoint (required for OpenAPI plugin to register routes)
            route("/swagger-ui/api.json") {
                openApi()
            }
        }
    }

    /**
     * Configure test authentication that bypasses JWT validation.
     * Uses a simple bearer token authentication for testing.
     */
    private fun Application.configureTestAuthentication() {
        install(Authentication) {
            bearer("test-auth") {
                realm = "test"
                authenticate { tokenCredential ->
                    // Extract NAVident from token (format: "test-token-{navIdent}")
                    val navIdent = tokenCredential.token.removePrefix("test-token-")
                    // Create a mock JWT payload with NAVident claim
                    val jwt = com.auth0.jwt.JWT.create()
                        .withIssuer(Config.issuers.head.issuer_name)
                        .withClaim("NAVident", navIdent)
                        .sign(com.auth0.jwt.algorithms.Algorithm.none())
                    io.ktor.server.auth.jwt.JWTPrincipal(com.auth0.jwt.JWT.decode(jwt))
                }
            }

            bearer("test-auth-admin") {
                realm = "test"
                authenticate { tokenCredential ->
                    // Create a mock JWT payload with NAVident claim and admin group
                    val jwt = com.auth0.jwt.JWT.create()
                        .withIssuer(Config.issuers.head.issuer_name)
                        .withClaim("NAVident", testAdminNavIdent)
                        .withArrayClaim("groups", arrayOf(Config.jwt.adminGroup))
                        .sign(com.auth0.jwt.algorithms.Algorithm.none())
                    io.ktor.server.auth.jwt.JWTPrincipal(com.auth0.jwt.JWT.decode(jwt))
                }
            }
        }
    }

    /**
     * Create a test HTTP client with JSON serialization configured.
     */
    protected fun ApplicationTestBuilder.createJsonClient(): HttpClient {
        return createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                })
            }
        }
    }

    /**
     * Add test authentication header to request.
     */
    protected fun HttpRequestBuilder.withTestAuth(navIdent: String = testNavIdent) {
        header("Authorization", "Bearer test-token-$navIdent")
    }

    /**
     * Add test admin authentication header to request.
     */
    protected fun HttpRequestBuilder.withTestAdminAuth() {
        header("Authorization", "Bearer test-admin-token")
    }
}

