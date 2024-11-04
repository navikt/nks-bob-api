package no.nav.nks_ai.core

import dev.starry.ktscheduler.job.Job
import dev.starry.ktscheduler.scheduler.KtScheduler
import dev.starry.ktscheduler.triggers.DailyTrigger
import no.nav.nks_ai.core.conversation.ConversationService

class ConversationDeletionJob(
    private val conversationService: ConversationService
) {
    fun start() {
        if (isLeader()) {
            scheduleJob()
        }
    }

    // TODO must be implemented before running multiple pods.
    //      See: https://doc.nais.io/services/leader-election/how-to/enable/#__tabbed_1_1
    private fun isLeader() = true

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