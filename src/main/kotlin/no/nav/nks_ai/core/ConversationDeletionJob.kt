package no.nav.nks_ai.core

import dev.starry.ktscheduler.job.Job
import dev.starry.ktscheduler.scheduler.KtScheduler
import dev.starry.ktscheduler.triggers.DailyTrigger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import no.nav.nks_ai.app.isLeader
import no.nav.nks_ai.core.conversation.ConversationService

private val logger = KotlinLogging.logger { }

class ConversationDeletionJob(
    private val conversationService: ConversationService,
    private val httpClient: HttpClient,
) {
    fun start() {
        scheduleJob()
    }

    // Deletes old conversations at 03:00 every night.
    private fun scheduleJob() {
        val scheduler = KtScheduler()
        val trigger = DailyTrigger(java.time.LocalTime.of(3, 0))
        val job = Job(
            jobId = "DeleteOldConversations",
            trigger = trigger,
            callback = {
                if (isLeader(httpClient)) {
                    conversationService.deleteOldConversations()
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
}