package no.nav.nks_ai.core.feedback

import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import no.nav.nks_ai.app.ApplicationError
import no.nav.nks_ai.app.respondError
import no.nav.nks_ai.app.respondResult

fun Route.feedbackAdminRoutes(feedbackService: FeedbackService) {
    route("/admin/feedbacks") {
        get({
            description = "Get all feedbacks"
            request {
                queryParameter<String>("filter") {
                    description =
                        "Filter which feedbacks will be returned (nye, ferdigstilte, viktige, særskilt-viktige)"
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
            val filter = call.queryParameters["filter"]
            val feedbacks = when (filter) {
                "nye" -> feedbackService.getFilteredFeedbacks(FeedbackFilter.Unresolved)
                "ferdigstilte" -> feedbackService.getFilteredFeedbacks(FeedbackFilter.Resolved)
                "viktige" -> feedbackService.getFilteredFeedbacks(FeedbackFilter.Important)
                "særskilt-viktige" -> feedbackService.getFilteredFeedbacks(FeedbackFilter.VeryImportant)
                else -> feedbackService.getAllFeedbacks()
            }

            call.respondResult(feedbacks)
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