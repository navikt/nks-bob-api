package no.nav.nks_ai.citation

import kotlinx.serialization.Serializable

@Serializable
data class Citation(
    val text: String,
    val article: String,
    val title: String,
    val section: String,
)

fun Citation.Companion.fromNewCitation(newCitation: NewCitation) =
    Citation(
        text = newCitation.text,
        article = newCitation.article,
        title = newCitation.title,
        section = newCitation.section,
    )

@Serializable
data class NewCitation(
    val text: String,
    val article: String,
    val title: String,
    val section: String,
)
