package no.nav.nks_ai.conversation

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.fold
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import no.nav.nks_ai.ApplicationError
import no.nav.nks_ai.SendMessageService
import no.nav.nks_ai.conversation.ConversationError.ConversationNotFound
import no.nav.nks_ai.fromThrowable
import no.nav.nks_ai.getNavIdent
import no.nav.nks_ai.message.Message
import no.nav.nks_ai.message.MessageRepo
import no.nav.nks_ai.message.NewMessage
import no.nav.nks_ai.now
import no.nav.nks_ai.respondError
import no.nav.nks_ai.sse
import no.nav.nks_ai.suspendTransaction
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.Collections
import java.util.UUID

object Conversations : UUIDTable() {
    val title = varchar("title", 255)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val owner = varchar("owner", 255)
}

class ConversationDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ConversationDAO>(Conversations)

    var title by Conversations.title
    var createdAt by Conversations.createdAt
    var owner by Conversations.owner
}

fun ConversationDAO.Companion.findByIdAndNavIdent(
    conversationId: UUID,
    navIdent: String
): ConversationDAO? =
    find {
        Conversations.id eq conversationId and (Conversations.owner eq navIdent)
    }.firstOrNull()

fun ConversationDAO.Companion.findAllByNavIdent(
    navIdent: String
): SizedIterable<ConversationDAO> = find { Conversations.owner eq navIdent }

fun ConversationDAO.toModel() = Conversation(
    id = id.toString(),
    title = title,
    createdAt = createdAt,
    owner = owner,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: LocalDateTime,
    val owner: String,
)

@Serializable
data class NewConversation(
    val title: String,
    val initialMessage: NewMessage?,
)

@Serializable
data class UpdateConversation(
    val title: String,
)

class ConversationRepo() {
    suspend fun addConversation(navIdent: String, conversation: NewConversation): Conversation =
        suspendTransaction {
            ConversationDAO.new {
                title = conversation.title
                owner = navIdent
            }.toModel()
        }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String): Unit =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)?.delete()
        }

    suspend fun deleteAllConversations(navIdent: String): Unit =
        suspendTransaction {
            ConversationDAO.findAllByNavIdent(navIdent).forEach { it.delete() }
        }

    suspend fun getConversation(conversationId: UUID, navIdent: String): Conversation? =
        suspendTransaction {
            ConversationDAO.findByIdAndNavIdent(conversationId, navIdent)
                ?.toModel()
        }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        suspendTransaction {
            ConversationDAO.find { Conversations.owner eq navIdent }
                .map { it.toModel() }
        }

    suspend fun updateConversation(id: UUID, navIdent: String, conversation: UpdateConversation): Conversation? =
        suspendTransaction {
            ConversationDAO
                .findByIdAndNavIdent(id, navIdent)
                ?.apply {
                    title = conversation.title
                }?.toModel()
        }
}

sealed class ConversationError(
    override val code: HttpStatusCode,
    override val message: String,
    override val description: String
) : ApplicationError(code, message, description) {
    class ConversationNotFound(id: UUID) : ConversationError(
        HttpStatusCode.NotFound,
        "Conversation not found",
        "Conversation with id $id not found"
    )
}

class ConversationService(
    private val conversationRepo: ConversationRepo,
    private val messageRepo: MessageRepo
) {
    suspend fun addConversation(navIdent: String, conversation: NewConversation) =
        conversationRepo.addConversation(navIdent, conversation)

    // TODO metrics
    suspend fun getConversation(
        conversationId: UUID,
        navIdent: String
    ): Either<ConversationNotFound, Conversation> =
        either {
            conversationRepo.getConversation(conversationId, navIdent)
                ?: raise(ConversationNotFound(conversationId))
        }

    suspend fun getAllConversations(navIdent: String): List<Conversation> =
        conversationRepo.getAllConversations(navIdent)

    suspend fun getConversationMessages(conversationId: UUID, navIdent: String): List<Message> {
        conversationRepo.getConversation(conversationId, navIdent)
            ?: return emptyList()

        return messageRepo.getMessagesByConversation(conversationId)
            .sortedBy { it.createdAt }
    }

    suspend fun deleteConversation(conversationId: UUID, navIdent: String): Unit =
        conversationRepo.deleteConversation(conversationId, navIdent)

    suspend fun updateConversation(id: UUID, navIdent: String, conversation: UpdateConversation) =
        conversationRepo.updateConversation(id, navIdent, conversation)
}

fun Route.conversationRoutes(
    conversationService: ConversationService,
    sendMessageService: SendMessageService
) {
    route("/conversations") {
        get(/* {
            description = "Get all of your conversations"
            response {
                HttpStatusCode.OK to {
                    description = "A list of your conversations"
                    body<List<Conversation>> {
                        description = "A list of your conversations"
                    }
                }
            }
        } */
        ) {
            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getAllConversations(navIdent)
                .let { call.respond(it) }
        }
        post(/*{
            description = "Create a new conversation"
            request {
                body<NewConversation> {
                    description = "The conversation to be created"
                }
            }
            response {
                HttpStatusCode.Created to {
                    description = "The conversation was created"
                    body<Conversation> {
                        description = "The conversation that got created"
                    }
                }
            }
        } */
        ) {
            coroutineScope {
                val newConversation = call.receiveNullable<NewConversation>()
                    ?: return@coroutineScope call.respond(HttpStatusCode.BadRequest)

                val navIdent = call.getNavIdent()
                    ?: return@coroutineScope call.respond(HttpStatusCode.Forbidden)

                val conversation = conversationService.addConversation(navIdent, newConversation)
                if (newConversation.initialMessage != null) {
                    launch(Dispatchers.Default) {
                        sendMessageService.sendMessageDelayed(
                            newConversation.initialMessage,
                            UUID.fromString(conversation.id),
                            navIdent
                        )
                    }
                }
                // TODO error?
                call.respond(HttpStatusCode.Created, conversation)
            }
        }
        get(
            "/{id}",
            /* {
                       description = "Get a conversation with the given ID"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<Conversation> {
                                   description = "The conversation requested"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            fold(
                block = { conversationService.getConversation(conversationId, navIdent) },
                transform = { call.respond(HttpStatusCode.OK, it) },
                recover = { error: ConversationError -> call.respondError(error) },
                catch = { call.respondError(ApplicationError.fromThrowable(it)) }
            )
        }
        delete(
            "/{id}",
            /* {
                       description = "Delete a conversation with the given ID"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.NoContent to {
                               description = "The operation was successful"
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@delete call.respond(HttpStatusCode.Forbidden)

            conversationService.deleteConversation(conversationId, navIdent)
            call.respond(HttpStatusCode.NoContent)
        }
        put(
            "/{id}",
            /* {
                       description = "Update a conversation with the given ID"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                           body<UpdateConversation> {
                               description = "The conversation request to update"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<Conversation> {
                                   description = "The updated conversation"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val conversation = call.receiveNullable<UpdateConversation>()
                ?: return@put call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@put call.respond(HttpStatusCode.Forbidden)

            val updatedConversation = conversationService.updateConversation(conversationId, navIdent, conversation)
            if (updatedConversation == null) {
                return@put call.respond(HttpStatusCode.NotFound)
            }

            call.respond(updatedConversation)
        }
        get(
            "/{id}/messages",
            /* {
                       description = "Get all messages for a given conversation"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<List<Message>> {
                                   description = "The messages in the conversation"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@get call.respond(HttpStatusCode.Forbidden)

            conversationService.getConversationMessages(conversationId, navIdent)
                .let { call.respond(it) }
        }
        post(
            "/{id}/messages",
            /* {
                       description = "Add a new message to the conversation"
                       request {
                           pathParameter<String>("id") {
                               description = "The ID of the conversation"
                           }
                           body<NewMessage> {
                               description = "The new message for the conversation"
                           }
                       }
                       response {
                           HttpStatusCode.OK to {
                               description = "The operation was successful"
                               body<List<Message>> {
                                   description = "The answer from KBS"
                               }
                           }
                       }
                   } */
        ) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val newMessage = call.receiveNullable<NewMessage>()
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@post call.respond(HttpStatusCode.Forbidden)

            val message = sendMessageService.sendMessage(newMessage, conversationId, navIdent)
            if (message == null) {
                return@post call.respond(HttpStatusCode.InternalServerError)
            }

            call.respond(message)
        }
        sse("/{id}/messages/stream", HttpMethod.Post) {
            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@sse call.respond(HttpStatusCode.BadRequest)

            val newMessage = call.receiveNullable<NewMessage>()
                ?: return@sse call.respond(HttpStatusCode.BadRequest)

            val navIdent = call.getNavIdent()
                ?: return@sse call.respond(HttpStatusCode.Forbidden)

            sendMessageService.sendMessageStream(newMessage, conversationId, navIdent)
                .flowOn(Dispatchers.Default)
                .onCompletion { cause ->
                    logger.debug { "receiving message closed. Cause: ${cause?.message}" }
                    close()
                }
                .onEach { message ->
                    logger.debug { "receiving message: ${message.content}" }
                }
                .collect {
                    send(ServerSentEvent(event = "message_chunk", data = Json.encodeToString(it)))
                }
        }
        val sessions = Collections.synchronizedList<WebSocketServerSession>(ArrayList())
        webSocket("/{id}/messages/ws") {
            val navIdent = call.getNavIdent()
                ?: return@webSocket call.respond(HttpStatusCode.Forbidden)

            val conversationId = call.parameters["id"]?.let { UUID.fromString(it) }
                ?: return@webSocket call.respond(HttpStatusCode.BadRequest)

            sessions.add(this)

            val existingMessages = conversationService.getConversationMessages(conversationId, navIdent)
            existingMessages.forEach { message ->
                sendSerialized(message)
            }

            while (true) {
                val messageEvent = receiveDeserialized<MessageEvent>()

                when (messageEvent) {
                    is MessageEvent.NewMessageEvent -> {
                        val newMessage = messageEvent.getData()
                        val channel = sendMessageService.sendMessageChannel(newMessage, conversationId, navIdent)
                        for (message in channel) {
                            for (session in sessions) {
                                session.sendSerialized(message)
                            }
                        }
                    }

                    is MessageEvent.UpdateMessageEvent -> {
                        val updateMessage = messageEvent.getData()
                        logger.debug { "Updating message ${updateMessage.id}" }
                    }

                    else -> {
                        logger.warn { "Unknown event type: ${messageEvent.eventType}" }
                    }
                }
            }
        }
    }
}

@Serializable
enum class MessageEventType {
    NewMessage,
    UpdateMessage,
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("eventType")
@Serializable
sealed class MessageEvent {
    abstract val eventType: MessageEventType
    abstract val data: JsonElement

    @Serializable
    @SerialName("NewMessage")
    data class NewMessageEvent(
        override val eventType: MessageEventType = MessageEventType.NewMessage,
        override val data: JsonElement
    ) : MessageEvent() {
        fun getData(): NewMessage = Json.decodeFromJsonElement(data)
    }

    @Serializable
    @SerialName("UpdateMessage")
    data class UpdateMessageEvent(
        override val eventType: MessageEventType = MessageEventType.UpdateMessage,
        override val data: JsonElement
    ) : MessageEvent() {
        fun getData(): Message = Json.decodeFromJsonElement(data)
    }
}

private val logger = KotlinLogging.logger {}
