package no.nav.nks_ai.core.feedback

import arrow.core.raise.either
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.pagination
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.app.respondResult

fun Route.feedbackAdminRoutes(feedbackService: FeedbackService) {
    route("/admin/feedbacks") {
        get({
            description = "Get all feedbacks"
            request {
                queryParameter<String>("filter") {
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
            }
            response {
                HttpStatusCode.OK to {
                    description = "The operation was successful"
                    body<List<Feedback>> {
                        description = "All feedbacks"
                    }
                }
            }
        }) {
            either {
                val pagination = call.pagination().bind()
                call.queryParameters["filter"]
                    ?.let {
                        val filter = FeedbackFilter.fromFilterValue(it).bind()
                        feedbackService.getFilteredFeedbacks(filter, pagination).bind()
                    }
                    ?: feedbackService.getAllFeedbacks(pagination).bind()
            }.let { call.respondResult(it) }
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
                val feedbackId = call.feedbackId()
                    ?: return@get call.respondError(ApplicationError.MissingFeedbackId())

                call.respondResult(feedbackService.getFeedback(feedbackId))
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
                val feedbackId = call.feedbackId()
                    ?: return@put call.respondError(ApplicationError.MissingFeedbackId())

                val createFeedback = call.receive<UpdateFeedback>()
                call.respondResult(feedbackService.updateFeedback(feedbackId, createFeedback))
            }
            /*patch({
                description = "Patch a feedback"
                request {
                    pathParameter<String>("id") {
                        description = "ID of the feedback"
                    }
                    body<PatchFeedback> {
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
                val feedbackId = call.feedbackId()
                    ?: return@patch call.respondError(ApplicationError.MissingFeedbackId())

                val patchFeedback = call.receive<PatchFeedback>()
                call.respondResult(feedbackService.patchFeedback(feedbackId, patchFeedback))
            }*/
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
                val feedbackId = call.feedbackId()
                    ?: return@delete call.respondError(ApplicationError.MissingFeedbackId())

                call.respondResult(feedbackService.deleteFeedback(feedbackId))
            }
        }
    }
}