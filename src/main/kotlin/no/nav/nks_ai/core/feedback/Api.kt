package no.nav.nks_ai.core.feedback

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import no.nav.nks_ai.app.Page
import no.nav.nks_ai.app.Sort
import no.nav.nks_ai.app.navIdent
import no.nav.nks_ai.app.pagination
import no.nav.nks_ai.app.respondEither
import no.nav.nks_ai.app.teamLogger

private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)

fun Route.feedbackAdminRoutes(feedbackService: FeedbackService) {
    route("/admin/feedbacks") {
        get({
            description = "Get all feedbacks"
            request {
                queryParameter<List<String>>("filter") {
                    description =
                        "Filter which feedbacks will be returned (${FeedbackFilter.validValues})"
                    required = false
                }
                queryParameter<Int>("page") {
                    description = "Which page to fetch (default = 0)"
                    required = false
                }
                queryParameter<Int>("size") {
                    description = "How many feedbacks to fetch (default = 100)"
                    required = false
                }
                queryParameter<String>("sort") {
                    description =
                        "Sort order (default = ${Sort.CreatedAtDesc.value}). Valid values: ${Sort.validValues}"
                    required = false
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<Page<Feedback>> {
                        description = "All feedbacks"
                    }
                }
            }
        }) {
            call.respondEither {
                val pagination = call.pagination().bind()
                val filters = call.queryParameters.getAll("filter")
                    ?.map { FeedbackFilter.fromFilterValue(it).bind() }
                    ?: emptyList()
                val navIdent = call.navIdent().bind()
                teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=LIST resource=feedbacks" }

                feedbackService.getFilteredFeedbacks(filters, pagination)
            }
        }

        route("/{id}") {
            get({
                description = "Get a feedback"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the feedback"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<Feedback> {
                            description = "The requested feedback"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val feedbackId = call.feedbackId().bind()
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=feedback/${feedbackId.value}" }

                    feedbackService.getFeedback(feedbackId)
                }
            }
            put({
                description = "Update a feedback"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the feedback"
                    }
                    body<UpdateFeedback> {
                        description = "The updated feedback"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "The operation was successful"
                        body<Feedback> {
                            description = "The requested feedback"
                        }
                    }
                }
            }) {
                call.respondEither {
                    val feedbackId = call.feedbackId().bind()
                    val updateFeedback = call.receive<UpdateFeedback>()
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=UPDATE resource=feedback/${feedbackId.value}" }

                    feedbackService.updateFeedback(feedbackId, updateFeedback)
                }
            }
            delete({
                description = "Delete a feedback"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the feedback"
                    }
                }
                response {
                    HttpStatusCode.NoContent to {
                        description = "The operation was successful"
                    }
                }
            }) {
                call.respondEither(HttpStatusCode.NoContent) {
                    val feedbackId = call.feedbackId().bind()
                    val navIdent = call.navIdent().bind()
                    teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=DELETE resource=feedback/${feedbackId.value}" }

                    feedbackService.deleteFeedback(feedbackId)
                }
            }
        }
    }
}