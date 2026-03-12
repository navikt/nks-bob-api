package no.nav.nks_ai.api.core.jobs

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import no.nav.nks_ai.api.app.respondEither
import no.nav.nks_ai.shared.DeleteOldConversationsSummary
import no.nav.nks_ai.shared.UploadStarredMessagesSummary

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
