package no.nav.nks_ai.api.core.jobs

import arrow.core.raise.either
import arrow.core.right
import arrow.core.separateEither
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.Config
import no.nav.nks_ai.api.app.respondEither
import no.nav.nks_ai.api.core.MarkMessageStarredService
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.shared.DeleteOldConversationsSummary
import no.nav.nks_ai.shared.UploadStarredMessagesSummary
import kotlin.time.Clock

val logger = KotlinLogging.logger {}

@OptIn(ExperimentalKtorApi::class)
fun Route.jobsRoutes(jobService: JobService) {
    route("/admin/jobs") {
        post("/delete-old-conversations") {
            call.respondEither {
                jobService.deleteOldConversations()
            }
        }.describe {
            description = "Delete old conversations"
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<DeleteOldConversationsSummary>()
                    description = "Summary of deleted conversations and messages"
                }
            }
        }
        post("/upload-starred-messages") {
            call.respondEither {
                jobService.uploadStarredMessages()
            }
        }.describe {
            description = "Upload starred messages"
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<UploadStarredMessagesSummary>()
                    description = "Summary of uploaded starred messages"
                }
            }
        }
    }
}
