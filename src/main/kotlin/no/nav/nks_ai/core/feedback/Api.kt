package no.nav.nks_ai.core.feedback

import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.Page
import no.nav.nks_ai.app.Sort
import no.nav.nks_ai.app.navIdent
import no.nav.nks_ai.app.pagination
import no.nav.nks_ai.app.respondEither
import no.nav.nks_ai.app.teamLogger

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

@OptIn(ExperimentalKtorApi::class)
fun Route.feedbackAdminRoutes(feedbackService: FeedbackService) {
    route("/admin/feedbacks") {
        get {
            call.respondEither {
                val pagination = call.pagination().bind()
                val filters = call.queryParameters.getAll("filter")
                    ?.map { FeedbackFilter.fromFilterValue(it).bind() }
                    ?: emptyList()
                val navIdent = call.navIdent().bind()
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=LIST resource=feedbacks" }

                feedbackService.getFilteredFeedbacks(filters, pagination)
            }
        }.describe {
            summary = "Get all feedbacks"
            parameters {
                query("filter") {
                    schema = jsonSchema<List<String>>()
                    description =
                        "Filter which feedbacks will be returned (${FeedbackFilter.validValues})"
                    required = false
                }
                query("page") {
                    schema = jsonSchema<Int>()
                    description = "Which page to fetch (default = 0)"
                    required = false
                }
                query("size") {
                    schema = jsonSchema<Int>()
                    description = "How many feedbacks to fetch (default = 100)"
                    required = false
                }
                query("sort") {
                    schema = jsonSchema<String>()
                    description =
                        "Sort order (default = ${Sort.CreatedAtDesc.value}). Valid values: ${Sort.validValues}"
                    required = false
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<Page<Feedback>>()
                    description = "All feedbacks"
                }
            }
        }

        route("/{id}") {
            get {
                call.respondEither {
                    val feedbackId = call.feedbackId().bind()
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=feedback/${feedbackId.value}" }

                    feedbackService.getFeedback(feedbackId)
                }
            }.describe {
                description = "Get a feedback"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the feedback"
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<Feedback>()
                        description = "The requested feedback"
                    }
                }
            }
            put {
                call.respondEither {
                    val feedbackId = call.feedbackId().bind()
                    val updateFeedback = call.receive<UpdateFeedback>()
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=UPDATE resource=feedback/${feedbackId.value}" }

                    feedbackService.updateFeedback(feedbackId, updateFeedback)
                }
            }.describe {
                description = "Update a feedback"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the feedback"
                    }
                }
                requestBody {
                    schema = jsonSchema<UpdateFeedback>()
                    description = "The updated feedback"
                }
                responses {
                    HttpStatusCode.OK {
                        schema = jsonSchema<Feedback>()
                        description = "The requested feedback"
                    }
                }
            }
            delete {
                call.respondEither(HttpStatusCode.NoContent) {
                    val feedbackId = call.feedbackId().bind()
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=DELETE resource=feedback/${feedbackId.value}" }

                    feedbackService.deleteFeedback(feedbackId)
                }
            }.describe {
                description = "Delete a feedback"
                parameters {
                    path("id") {
                        schema = jsonSchema<String>()
                        description = "ID of the feedback"
                    }
                }
                responses {
                    HttpStatusCode.NoContent {
                        description = "The operation was successful"
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
fun Route.feedbackAdminBatchRoutes(feedbackService: FeedbackService) {
    route("/admin/feedbacks_batch") {
        put {
            call.respondEither<BatchResolveFeedbacksResponse> {
                val beforeRaw = call.request.queryParameters["before"]
                    ?: raise(ApplicationError.BadRequest("Missing required query parameter 'before'"))

                val note = call.request.queryParameters["note"]
                    ?: raise(ApplicationError.BadRequest("Missing required query parameter 'note'"))

                val before = runCatching { LocalDateTime.parse(beforeRaw) }
                    .getOrElse {
                        raise(ApplicationError.BadRequest("Invalid 'before' value. Expected ISO-8601 LocalDateTime, got: $beforeRaw"))
                    }

                val navIdent = call.navIdent().bind()
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=BATCH_RESOLVE resource=feedbacks before=$beforeRaw" }

                val updated = feedbackService.batchResolveFeedbacksBefore(before, note).bind()
                BatchResolveFeedbacksResponse(updated = updated, before = beforeRaw).right()
            }
        }.describe {
            description = "Batch resolve (DateExpired) all unresolved feedbacks created before supplied date"
            parameters {
                query("before") {
                    schema = jsonSchema<String>()
                    description =
                        "Resolve feedbacks created before this timestamp (ISO-8601 LocalDateTime, e.g. 2026-02-18T12:30:00)"
                    required = true
                }
                query("note") {
                    schema = jsonSchema<String>()
                    description = "Resolve feedbacks with this note"
                    required = true
                }
            }
            responses {
                HttpStatusCode.OK {
                    schema = jsonSchema<BatchResolveFeedbacksResponse>()
                    description = "Result of the batch operation"
                }
            }
        }
    }
}

@Serializable
data class BatchResolveFeedbacksResponse(
    val updated: Int,
    val before: String
)