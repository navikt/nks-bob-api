package no.nav.nks_ai.feedback

import kotlinx.serialization.Serializable

@Serializable
data class Feedback(
    val liked: Boolean,
)

fun Feedback.Companion.fromNewFeedback(newFeedback: NewFeedback) =
    Feedback(
        liked = newFeedback.liked
    )

@Serializable
data class NewFeedback(
    val liked: Boolean,
)