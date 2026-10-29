package no.nav.nks_ai.core.integration

import arrow.core.left
import arrow.core.right
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.get
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nav.nks_ai.api.app.ApplicationError
import no.nav.nks_ai.api.app.ApplicationResult
import no.nav.nks_ai.api.app.FeatureToggles
import no.nav.nks_ai.api.core.conversation.Conversation
import no.nav.nks_ai.api.core.conversation.ConversationId
import no.nav.nks_ai.api.core.conversation.ConversationRepo
import no.nav.nks_ai.api.core.conversation.ConversationService
import no.nav.nks_ai.api.core.conversation.NewConversation
import no.nav.nks_ai.api.core.integration.IntegrationConversationService
import no.nav.nks_ai.api.core.message.Message
import no.nav.nks_ai.api.core.message.MessageId
import no.nav.nks_ai.api.core.message.MessageRepo
import no.nav.nks_ai.api.core.message.MessageService
import no.nav.nks_ai.api.core.message.NewMessage
import no.nav.nks_ai.api.core.user.NavIdent
import no.nav.nks_ai.api.v2.core.SendMessageService
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEvent
import no.nav.nks_ai.api.v2.core.conversation.streaming.ConversationEventBus
import no.nav.nks_ai.api.vaskemaskin.VaskemaskinClient
import no.nav.nks_ai.testutil.TestDatabase
import no.nav.nks_ai.testutil.testApp
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class IntegrationConversationServiceTest {
    @Test
    fun `invalid owner is rejected before content processing and writing`() = integrationTest {
        every { toggles.isVaskemaskinAnonymizationEnabled() } returns true
        val result = service.createConversation(
            NavIdent("not-an-ident"),
            NewConversation("Do not process", NewMessage("Question")),
        )

        assertIs<ApplicationError.BadRequest>(result.leftOrNull())
        assertTrue(MessageRepo.getMessagesByConversation(existing.id).value().isEmpty())
        coVerify(exactly = 0) { vaskemaskin.anonymize(any()) }
        coVerify(exactly = 0) { vaskemaskin.detect(any()) }
        coVerify(exactly = 0) { sender.askQuestion(any(), any(), any(), any()) }
    }

    @Test
    fun `invalid input is rejected without writing resources`() = integrationTest {
        for (title in listOf("", "  ", "x".repeat(256))) {
            assertIs<ApplicationError.BadRequest>(
                service.createConversation(owner, NewConversation(title, null)).leftOrNull()
            )
        }
        assertIs<ApplicationError.BadRequest>(
            service.createConversation(owner, NewConversation("Valid title", NewMessage(" "))).leftOrNull()
        )
        assertIs<ApplicationError.BadRequest>(
            service.createConversation(
                owner,
                NewConversation("Valid title", NewMessage("x".repeat(20_001))),
            ).leftOrNull()
        )
        assertEquals(1, ConversationRepo.getAllConversations(owner).value().size)
        assertTrue(MessageRepo.getMessagesByConversation(existing.id).value().isEmpty())
        coVerify(exactly = 0) { sender.askQuestion(any(), any(), any(), any()) }
    }

    @Test
    fun `separate creations have different ids and leave an existing conversation unchanged`() = integrationTest {
        val existingMessage = MessageRepo.addQuestion(existing.id, owner, "Existing question").value()
        val existingBefore = ConversationRepo.getConversation(existing.id, owner).value()

        val first = service.createConversation(owner, NewConversation("First new conversation", null)).value()
        val second = service.createConversation(owner, NewConversation("Second new conversation", null)).value()

        assertNotEquals(first.id, second.id)
        assertEquals(existingBefore, ConversationRepo.getConversation(existing.id, owner).value())
        assertEquals(listOf(existingMessage), MessageRepo.getMessagesByConversation(existing.id).value())
        assertTrue(MessageRepo.getMessagesByConversation(first.id).value().isEmpty())
        assertTrue(MessageRepo.getMessagesByConversation(second.id).value().isEmpty())
        assertEquals(3, ConversationRepo.getAllConversations(owner).value().size)
    }

    @Test
    fun `new conversation preserves a maximum length title without starting processing`() = integrationTest {
        val title = "x".repeat(255)
        val created = service.createConversation(owner, NewConversation(title, null)).value()
        assertEquals(title, created.title)
        assertTrue(MessageRepo.getMessagesByConversation(created.id).value().isEmpty())
        coVerify(exactly = 0) { sender.askQuestion(any(), any(), any(), any()) }
    }

    @Test
    fun `initial question is anonymized once and processing receives the stored text`() = integrationTest {
        every { toggles.isVaskemaskinAnonymizationEnabled() } returns true
        coEvery { vaskemaskin.anonymize("Original question") } returns "Processed question".right()
        val started = CompletableDeferred<String>()
        coEvery { sender.askQuestion(any(), any(), any(), any()) } coAnswers {
            started.complete(firstArg<Message>().content)
            emptyFlow<ConversationEvent>().right()
        }
        val created = service.createConversation(
            owner, NewConversation("New conversation", NewMessage("Original question")),
        ).value()
        val question = MessageRepo.getMessagesByConversation(created.id).value().single()
        assertEquals("Processed question", question.content)
        assertEquals(question.content, withTimeout(5_000) { started.await() })
        coVerify(exactly = 1) { vaskemaskin.anonymize("Original question") }
    }

    @Test
    fun `content processing failure does not leave an empty conversation`() = integrationTest {
        every { toggles.isVaskemaskinAnonymizationEnabled() } returns true
        coEvery { vaskemaskin.anonymize(any()) } returns
            ApplicationError.InternalServerError("Unavailable", "Content processing failed").left()

        assertTrue(
            service.createConversation(owner, NewConversation("Not saved", NewMessage("Question"))).isLeft()
        )
        assertEquals(1, ConversationRepo.getAllConversations(owner).value().size)
        coVerify(exactly = 0) { sender.askQuestion(any(), any(), any(), any()) }
    }

    @Test
    fun `initial question insertion failure rolls back the conversation too`() = integrationTest {
        DriverManager.getConnection(
            TestDatabase.jdbcUrl, TestDatabase.container.username, TestDatabase.container.password,
        ).use { connection ->
            connection.createStatement().use {
                it.execute(
                    "alter table messages add constraint integration_initial_message_test check (content <> 'reject-initial')"
                )
            }
            try {
                val result = service.createConversation(
                    owner, NewConversation("Must roll back", NewMessage("reject-initial")),
                )
                assertTrue(result.isLeft())
                assertEquals(listOf(existing.id), ConversationRepo.getAllConversations(owner).value().map { it.id })
                coVerify(exactly = 0) { sender.askQuestion(any(), any(), any(), any()) }
            } finally {
                connection.createStatement().use {
                    it.execute("alter table messages drop constraint integration_initial_message_test")
                }
            }
        }
    }

    @Test
    fun `initial message processing survives the request scope and does not require an active conversation`() = integrationTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        coEvery { sender.askQuestion(any(), any(), any(), any()) } returns flow<ConversationEvent> {
            started.complete(Unit)
            release.await()
            finished.complete(Unit)
        }.right()
        val requestJob = Job()
        try {
            val created = withContext(requestJob) {
                service.createConversation(owner, NewConversation("New conversation", NewMessage("Question"))).value()
            }
            val question = MessageRepo.getMessagesByConversation(created.id).value().single()
            assertEquals("Question", messages.getMessage(question.id).value().content)
            withTimeout(5_000.milliseconds) { started.await() }
            requestJob.cancelAndJoin()
            assertFalse(finished.isCompleted)
            release.complete(Unit)
            withTimeout(5_000.milliseconds) { finished.await() }
        } finally {
            requestJob.cancelAndJoin()
        }
    }

    @Test
    fun `failures both before and during flow collection are persisted and published`() = integrationTest {
        for (duringCollection in listOf(false, true)) {
            val published = CompletableDeferred<Unit>()
            every { events.publish(any(), any()) } answers {
                if (secondArg<ConversationEvent>() is ConversationEvent.ErrorsUpdated) published.complete(Unit)
            }
            coEvery { sender.askQuestion(any(), any(), any(), any()) } coAnswers {
                if (!duringCollection) throw IllegalStateException("Sensitive upstream detail")
                flow<ConversationEvent> { throw IllegalStateException("Sensitive upstream detail") }.right()
            }
            val created = service.createConversation(
                owner,
                NewConversation("New conversation", NewMessage("Question")),
            ).value()
            withTimeout(5_000) { published.await() }
            val failed = MessageRepo.getMessagesByConversation(created.id).value().single()
            assertFalse(failed.pending)
            assertTrue(failed.errors.isNotEmpty())
            assertTrue(failed.errors.none { it.description.contains("Sensitive upstream detail") })
        }
    }

    @Test
    fun `startup domain error is recorded on the question`() = integrationTest {
        val published = CompletableDeferred<Unit>()
        every { events.publish(any(), any()) } answers {
            if (secondArg<ConversationEvent>() is ConversationEvent.ErrorsUpdated) published.complete(Unit)
        }
        coEvery { sender.askQuestion(any(), any(), any(), any()) } returns
            ApplicationError.InternalServerError("Unavailable", "Failed to create answer").left()
        val created = service.createConversation(
            owner,
            NewConversation("New conversation", NewMessage("Question")),
        ).value()
        withTimeout(5_000.milliseconds) { published.await() }
        assertTrue(MessageRepo.getMessagesByConversation(created.id).value().single().errors.isNotEmpty())
    }

    @Test
    fun `shutdown marks an interrupted initial answer as failed and waits for cleanup`() = integrationTest {
        val started = CompletableDeferred<MessageId>()
        coEvery { sender.askQuestion(any(), any(), any(), any()) } coAnswers {
            val conversationId = ConversationId(secondArg())
            val answer = messages.addEmptyAnswer(conversationId).value()
            arg<(MessageId) -> Unit>(3)(answer.id)
            flow<ConversationEvent> {
                started.complete(answer.id)
                awaitCancellation()
            }.right()
        }
        service.createConversation(
            owner,
            NewConversation("New conversation", NewMessage("Question")),
        ).value()
        val answerId = withTimeout(5_000.milliseconds) { started.await() }
        service.shutdown()
        val failed = messages.getMessage(answerId).value()
        assertFalse(failed.pending)
        assertTrue(failed.errors.isNotEmpty())
        assertTrue(
            service.createConversation(owner, NewConversation("Too late", NewMessage("Question"))).isLeft()
        )
    }

    @Test
    fun `immediate shutdown also records failure for an accepted task waiting to run`() = integrationTest {
        coEvery { sender.askQuestion(any(), any(), any(), any()) } returns
            flow<ConversationEvent> { awaitCancellation() }.right()
        val created = service.createConversation(
            owner,
            NewConversation("New conversation", NewMessage("Question")),
        ).value()
        service.shutdown()
        assertTrue(MessageRepo.getMessagesByConversation(created.id).value().single().errors.isNotEmpty())
    }

    @Test
    fun `rejected handoff reports the persisted resource ids instead of success`() = integrationTest {
        val controlledMessages = mockk<MessageService>()
        coEvery { controlledMessages.addConversationWithQuestion(any(), any(), any()) } coAnswers {
            messages.addConversationWithQuestion(firstArg(), secondArg(), thirdArg()).also { parent.cancel() }
        }
        coEvery { controlledMessages.updateMessageError(any(), any(), any()) } coAnswers {
            messages.updateMessageError(MessageId(firstArg()), secondArg(), thirdArg())
        }
        val controlledService = IntegrationConversationService.create(
            ConversationService(), controlledMessages, sender, events, CoroutineScope(parent),
        )
        try {
            val error = assertIs<ApplicationError.MessageProcessingNotStarted>(
                controlledService.createConversation(
                    owner,
                    NewConversation("New before shutdown", NewMessage("Saved before shutdown")),
                ).leftOrNull()
            )
            assertEquals(HttpStatusCode.Conflict, error.code)
            assertNotEquals(existing.id, error.conversationId)
            val saved = messages.getMessage(error.messageId).value()
            assertEquals("Saved before shutdown", saved.content)
            assertTrue(saved.errors.isNotEmpty())
            coVerify(exactly = 0) { sender.askQuestion(any(), any(), any(), any()) }
        } finally {
            controlledService.shutdown()
        }
    }

    private fun integrationTest(block: suspend Fixture.() -> Unit) = testApp { client ->
        client.get("/internal/is_alive")
        val fixture = Fixture()
        try {
            fixture.existing = ConversationRepo.addConversation(
                fixture.owner, NewConversation("Existing conversation", null),
            ).value()
            fixture.block()
        } finally {
            fixture.service.shutdown()
            fixture.parent.cancelAndJoin()
            ConversationRepo.getAllConversations(fixture.owner).value().forEach {
                ConversationRepo.deleteConversation(it.id, fixture.owner).value()
            }
        }
    }

    private class Fixture {
        val owner = NavIdent("T300001")
        val parent = SupervisorJob()
        val toggles = mockk<FeatureToggles>(relaxed = true)
        val vaskemaskin = mockk<VaskemaskinClient>()
        val messages = MessageService(vaskemaskin, toggles, CoroutineScope(parent))
        val sender = mockk<SendMessageService> {
            coEvery { askQuestion(any(), any(), any(), any()) } returns emptyFlow<ConversationEvent>().right()
        }
        val events = mockk<ConversationEventBus>(relaxed = true)
        val service = IntegrationConversationService.create(
            ConversationService(), messages, sender, events, CoroutineScope(parent),
        )
        lateinit var existing: Conversation
    }
}

private fun <T : Any> ApplicationResult<T>.value(): T =
    assertNotNull(getOrNull(), leftOrNull()?.description)
