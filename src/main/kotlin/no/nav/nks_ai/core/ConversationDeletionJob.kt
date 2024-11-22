package no.nav.nks_ai.core

import dev.starry.ktscheduler.job.Job
import dev.starry.ktscheduler.scheduler.KtScheduler
import dev.starry.ktscheduler.triggers.DailyTrigger
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import no.nav.nks_ai.app.isLeader
import no.nav.nks_ai.core.conversation.ConversationService

class ConversationDeletionJob(
    private val conversationService: ConversationService,
    private val httpClient: HttpClient,
) {
    fun start() = runBlocking {
        if (isLeader(httpClient)) {
            scheduleJob()
        }
    }

    // Deletes old conversations at 03:00 every night.
    private fun scheduleJob() {
        val scheduler = KtScheduler()
        val trigger = DailyTrigger(java.time.LocalTime.of(3, 0))
        val job = Job(
            jobId = "DeleteOldConversations",
            trigger = trigger,
            callback = {
                conversationService.deleteOldConversations()
            }
        )

        scheduler.addJob(job)
        scheduler.start()
    }
}