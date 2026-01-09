package no.nav.nks_ai.integration

import arrow.core.Option
import arrow.core.getOrElse
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.core.conversation.ConversationRepo
import no.nav.nks_ai.core.conversation.NewConversation
import no.nav.nks_ai.core.message.*
import no.nav.nks_ai.core.user.NavIdent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for MessageRepo.
 * Tests actual database operations against a real PostgreSQL database in Docker.
 */
class MessageIntegrationTest : IntegrationTestBase() {

    private val testNavIdent = NavIdent("Z999999")

    private suspend fun createTestConversation(title: String = "Test Conversation") =
        ConversationRepo.addConversation(
            testNavIdent,
            NewConversation(title, null)
        ).getOrElse { throw AssertionError("Failed to create test conversation") }

    @Test
    fun `addMessage should create new message in database`() = runBlocking {
        val conversation = createTestConversation()

        val result = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Hello, this is a test message",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "test-user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        )

        assertTrue(result.isRight())
        val message = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals("Hello, this is a test message", message.content)
        assertEquals(MessageType.Question, message.messageType)
        assertEquals(MessageRole.Human, message.messageRole)
    }

    @Test
    fun `getMessage should retrieve specific message by id`() = runBlocking {
        val conversation = createTestConversation()
        val created = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Retrieve me",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        val result = MessageRepo.getMessage(created.id)

        assertTrue(result.isRight())
        val message = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals(created.id, message.id)
        assertEquals("Retrieve me", message.content)
    }

    @Test
    fun `getMessage should return error for non-existent id`() = runBlocking {
        val fakeId = java.util.UUID.randomUUID()
        val result = MessageRepo.getMessage(MessageId(fakeId))

        assertTrue(result.isLeft())
    }

    @Test
    fun `getMessagesByConversation should return all messages for conversation`() = runBlocking {
        val conversation = createTestConversation()

        MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "First message",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        )

        MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Second message",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        )

        val result = MessageRepo.getMessagesByConversation(conversation.id)

        assertTrue(result.isRight())
        val messages = result.getOrElse { throw AssertionError("Expected Right") }
        assertTrue(messages.size >= 2)
        assertEquals("First message", messages[0].content)
        assertEquals("Second message", messages[1].content)
    }

    @Test
    fun `updateMessage should update all fields`() = runBlocking {
        val conversation = createTestConversation()
        val created = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Original content",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = true
        ).getOrElse { throw AssertionError("Expected Right") }

        val testContext = Context(
            content = "test content",
            title = "test title",
            ingress = "test ingress",
            source = "test source",
            url = "http://test.url",
            anchor = null,
            articleId = "test-article-id",
            articleColumn = null,
            lastModified = null,
            semanticSimilarity = 0.9
        )

        val result = MessageRepo.updateMessage(
            messageId = created.id,
            messageContent = "Updated content",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = listOf(testContext),
            citations = listOf(Citation("test text", 1)),
            followUp = listOf("Follow up 1", "Follow up 2"),
            pending = false,
            userQuestion = "What is the weather?",
            contextualizedQuestion = "What is the weather in Oslo today?"
        )

        assertTrue(result.isRight())
        val updated = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals("Updated content", updated.content)
        assertEquals(MessageRole.AI, updated.messageRole)
        assertEquals(false, updated.pending)
        assertEquals("What is the weather?", updated.userQuestion)
        assertEquals("What is the weather in Oslo today?", updated.contextualizedQuestion)
        assertEquals(1, updated.context.size)
        assertEquals(1, updated.citations.size)
        assertEquals(2, updated.followUp.size)
    }

    @Test
    fun `patchMessage should update only specified fields`() = runBlocking {
        val conversation = createTestConversation()
        val created = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Original content",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = true
        ).getOrElse { throw AssertionError("Expected Right") }

        val result = MessageRepo.patchMessage(
            messageId = created.id,
            messageContent = Option.fromNullable("Patched content"),
            pending = Option.fromNullable(false)
        )

        assertTrue(result.isRight())
        val patched = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals("Patched content", patched.content)
        assertEquals(false, patched.pending)
        assertEquals(MessageRole.Human, patched.messageRole) // Should remain unchanged
    }

    @Test
    fun `patchMessage should accumulate errors`() = runBlocking {
        val conversation = createTestConversation()
        val created = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Test message",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        // Add first error
        MessageRepo.patchMessage(
            messageId = created.id,
            errors = Option.fromNullable(listOf(MessageError("Error 1", "First error description")))
        )

        // Add second error
        val result = MessageRepo.patchMessage(
            messageId = created.id,
            errors = Option.fromNullable(listOf(MessageError("Error 2", "Second error description")))
        )

        assertTrue(result.isRight())
        val updated = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals(2, updated.errors.size)
    }

    @Test
    fun `starred message operations should work correctly`() = runBlocking {
        val conversation = createTestConversation()
        val message = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Star me",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        // Mark as starred
        MessageRepo.patchMessage(
            messageId = message.id,
            starred = Option.fromNullable(true)
        )

        // Should appear in not uploaded list
        val notUploadedResult = MessageRepo.getStarredMessagesNotUploaded()
        assertTrue(notUploadedResult.isRight())
        val notUploaded = notUploadedResult.getOrElse { throw AssertionError("Expected Right") }
        assertTrue(notUploaded.any { it.id == message.id })

        // Mark as uploaded
        MessageRepo.markStarredMessageUploaded(message.id)

        // Should no longer appear in not uploaded list
        val afterUploadResult = MessageRepo.getStarredMessagesNotUploaded()
        val afterUpload = afterUploadResult.getOrElse { throw AssertionError("Expected Right") }
        assertTrue(afterUpload.none { it.id == message.id })
    }

    @Test
    fun `getConversationId should return conversation id for message`() = runBlocking {
        val conversation = createTestConversation()
        val message = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Test",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        val result = MessageRepo.getConversationId(message.id)

        assertTrue(result.isRight())
        val conversationId = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals(conversation.id, conversationId)
    }

    @Test
    fun `getOwner should return owner navIdent for message`() = runBlocking {
        val conversation = createTestConversation()
        val message = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Test",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        val result = MessageRepo.getOwner(message.id)

        assertTrue(result.isRight())
        val owner = result.getOrElse { throw AssertionError("Expected Right") }
        // Owner is stored as bcrypt hash
        assertTrue(owner.isNotEmpty())
    }

    @Test
    fun `deleteMessages should remove messages from database`() = runBlocking {
        val conversation = createTestConversation()
        val message1 = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Delete me 1",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        val message2 = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Delete me 2",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        ).getOrElse { throw AssertionError("Expected Right") }

        val deleteResult = MessageRepo.deleteMessages(listOf(message1.id, message2.id))
        assertTrue(deleteResult.isRight())
        val deletedCount = deleteResult.getOrElse { throw AssertionError("Expected Right") }
        assertEquals(2, deletedCount)

        // Verify messages are deleted
        val getMessage1 = MessageRepo.getMessage(message1.id)
        assertTrue(getMessage1.isLeft())

        val getMessage2 = MessageRepo.getMessage(message2.id)
        assertTrue(getMessage2.isLeft())
    }

    @Test
    fun `getMessagesCreatedBefore should return messages before date`() = runBlocking {
        val conversation = createTestConversation()

        // Create a message
        MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Old message",
            messageType = MessageType.Question,
            messageRole = MessageRole.Human,
            createdBy = "user",
            context = emptyList(),
            citations = emptyList(),
            pending = false
        )

        // Query for messages created before a future date
        val futureDate = LocalDateTime.parse("2030-12-31T23:59:59")
        val result = MessageRepo.getMessagesCreatedBefore(futureDate)

        assertTrue(result.isRight())
        val messages = result.getOrElse { throw AssertionError("Expected Right") }
        assertTrue(messages.isNotEmpty())
    }

    @Test
    fun `messages should support context and citations`() = runBlocking {
        val conversation = createTestConversation()

        val context = listOf(
            Context(
                content = "Context content 1",
                title = "Context title 1",
                ingress = "Context ingress 1",
                source = "Context source 1",
                url = "http://example.com/1",
                anchor = null,
                articleId = "ctx-1",
                articleColumn = null,
                lastModified = null,
                semanticSimilarity = 0.95
            ),
            Context(
                content = "Context content 2",
                title = "Context title 2",
                ingress = "Context ingress 2",
                source = "Context source 2",
                url = "http://example.com/2",
                anchor = null,
                articleId = "ctx-2",
                articleColumn = null,
                lastModified = null,
                semanticSimilarity = 0.92
            )
        )

        val citations = listOf(
            Citation("Citation text 1", 1),
            Citation("Citation text 2", 2)
        )

        val result = MessageRepo.addMessage(
            conversationId = conversation.id,
            messageContent = "Message with context and citations",
            messageType = MessageType.Answer,
            messageRole = MessageRole.AI,
            createdBy = "assistant",
            context = context,
            citations = citations,
            pending = false
        )

        assertTrue(result.isRight())
        val message = result.getOrElse { throw AssertionError("Expected Right") }
        assertEquals(2, message.context.size)
        assertEquals(2, message.citations.size)
        assertEquals("Context content 1", message.context[0].content)
        assertEquals("Citation text 1", message.citations[0].text)
    }
}
