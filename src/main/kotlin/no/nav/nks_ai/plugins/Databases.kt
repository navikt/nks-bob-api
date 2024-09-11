package no.nav.nks_ai.plugins

import io.ktor.server.application.*
import kotlinx.datetime.LocalDateTime
import no.nav.nks_ai.conversation.ConversationDAO
import no.nav.nks_ai.conversation.Conversations
import no.nav.nks_ai.feedback.FeedbackDAO
import no.nav.nks_ai.feedback.Feedbacks
import no.nav.nks_ai.message.MessageDAO
import no.nav.nks_ai.message.MessageRole
import no.nav.nks_ai.message.MessageType
import no.nav.nks_ai.message.Messages
import no.nav.nks_ai.now
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun Application.configureDatabases() {
    // TODO postgres setup
    val database = Database.connect(
        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        user = "root",
        driver = "org.h2.Driver",
        password = "",
    )

    // TODO flyway migrations
    transaction(database) {
        SchemaUtils.create(Conversations, Messages, Feedbacks)

        // test data
        val conversation1 = ConversationDAO.new(UUID.fromString("6cf0b651-e5f1-4148-a2e1-9634e6cfa29e")) {
            this.createdAt = LocalDateTime.now()
            this.title = "test conversation"
            this.owner = "Z123456"
        }

        MessageDAO.new(UUID.fromString("0eb79520-93a2-41aa-aa88-819fe15600e0")) {
            this.content = "message #1"
            this.createdAt = LocalDateTime.now()
            this.conversation = conversation1
            this.messageRole = MessageRole.Human
            this.messageType = MessageType.Question
            this.createdBy = "Z123456"
        }

        val conversation2 = ConversationDAO.new {
            this.createdAt = LocalDateTime.now()
            this.title = "another conversation"
            this.owner = "Z654321"
        }

        val feedback = FeedbackDAO.new {
            this.liked = true
            this.createdAt = LocalDateTime.now()
        }

        MessageDAO.new {
            this.content = "message #2"
            this.createdAt = LocalDateTime.now()
            this.conversation = conversation2
            this.feedback = feedback
            this.messageRole = MessageRole.Human
            this.messageType = MessageType.Question
            this.createdBy = "Z654321"
        }
    }
}
