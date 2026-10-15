package no.nav.nks_ai.core.conversation.active

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import no.nav.nks_ai.api.app.Page
import no.nav.nks_ai.api.app.Sort
import no.nav.nks_ai.api.core.conversation.active.ActiveConversation
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationPageRequest
import no.nav.nks_ai.api.core.conversation.active.ActiveConversationSort
import no.nav.nks_ai.api.core.conversation.toConversationId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ActiveConversationModelTest {
    @Test
    fun `serializes one conversation without connection or owner identifiers`() {
        val conversation = ActiveConversation(
            id = UUID.randomUUID().toConversationId(),
            title = "Example",
            createdAt = LocalDateTime(2026, 9, 7, 8, 0),
            connectedAt = LocalDateTime(2026, 9, 7, 9, 0),
        )

        val json = Json.encodeToString(Page(listOf(conversation), 1))

        assertEquals(Page(listOf(conversation), 1), Json.decodeFromString<Page<ActiveConversation>>(json))
        assertFalse(json.contains("owner"))
        assertFalse(json.contains("connectionId"))
    }

    @Test
    fun `defaults to the existing feedback pagination conventions`() {
        val request = ActiveConversationPageRequest()

        assertEquals(0, request.page)
        assertEquals(100, request.size)
        assertEquals(Sort.CreatedAtDesc.value, request.sort.name)
    }

    @Test
    fun `connection sort options do not change existing API sort options`() {
        assertEquals(
            setOf("CREATED_AT_ASC", "CREATED_AT_DESC", "CONNECTED_AT_ASC", "CONNECTED_AT_DESC"),
            ActiveConversationSort.entries.map { it.name }.toSet(),
        )
        assertEquals(setOf("CREATED_AT_ASC", "CREATED_AT_DESC"), Sort.entries.map { it.value }.toSet())
    }
}
