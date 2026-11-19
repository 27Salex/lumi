package io.github.salex27.lumi.service.relay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.salex27.lumi.TaskManagerApplication
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (Android's minimum; Doze may stretch it): closes the reminders and schedule blocks whose time is
 * over, and applies the changes Claude queued through the connector while Lumi was closed.
 */
class LumiSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TaskManagerApplication
        runCatching { app.sweepSchedule() }
        runCatching { app.claudeRelay.syncNow() }
        return Result.success()
    }

    companion object {
        private const val NAME = "lumi_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LumiSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
