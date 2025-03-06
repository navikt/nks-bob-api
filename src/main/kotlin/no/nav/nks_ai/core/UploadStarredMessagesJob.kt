package no.nav.nks_ai.core

import arrow.core.separateEither
import dev.starry.ktscheduler.job.Job
import dev.starry.ktscheduler.scheduler.KtScheduler
import dev.starry.ktscheduler.triggers.IntervalTrigger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import no.nav.nks_ai.app.isLeader
import no.nav.nks_ai.core.message.MessageId
import no.nav.nks_ai.core.message.MessageService
import kotlin.collections.map

private val logger = KotlinLogging.logger { }

private const val ONE_HOUR_SECONDS: Long = 60 * 60

class UploadStarredMessagesJob(
    private val messageService: MessageService,
    private val markMessageStarredService: MarkMessageStarredService,
    private val httpClient: HttpClient,
) {
    fun start() {
        scheduleJob()
    }

    private fun scheduleJob() {
        val scheduler = KtScheduler()
        val trigger = IntervalTrigger(ONE_HOUR_SECONDS)
        val job = Job(
            jobId = "UploadStarredMessages",
            trigger = trigger,
            callback = {
                if (isLeader(httpClient)) {
                    synchronizeStarredMessages()
                } else {
                    logger.info {
                        "This instance is not leader. Scheduled job will not be performed"
                    }
                }
            }
        )

        scheduler.addJob(job)
        scheduler.start()
    }

    private suspend fun synchronizeStarredMessages() {
        val messages: List<MessageId> = messageService.getStarredMessagesNotUploaded().map { it.id }
        logger.info { "Found ${messages.size} starred messages" }

        val (errors, uploadedMessages) = messages.map { messageId ->
            markMessageStarredService.markStarred(messageId)
        }.separateEither()

        if (uploadedMessages.isNotEmpty()) {
            logger.info { "Uploaded ${uploadedMessages.size} starred messages" }
        }

        if (errors.isNotEmpty()) {
            val errorDetails = errors.map { "${it.message}: ${it.description}" }
                .distinct().joinToString(", ")

            logger.error { "Error when uploading ${errors.size} starred messages: $errorDetails" }
        }
    }
}
