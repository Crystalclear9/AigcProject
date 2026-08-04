package com.suishouban.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.suishouban.app.data.local.AppDatabase
import com.suishouban.app.data.local.toDomain
import com.suishouban.app.data.local.toEntity
import com.suishouban.app.domain.planning.PriorityPlanner
import java.util.concurrent.TimeUnit

class PriorityCalibrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).cardDao()
        val scheduler = ReminderScheduler(applicationContext)
        dao.adaptiveCards().forEach { entity ->
            val current = entity.toDomain()
            val calibrated = PriorityPlanner.calibrate(current)
            if (
                calibrated.priority != current.priority ||
                calibrated.priorityScore != current.priorityScore ||
                calibrated.priorityReason != current.priorityReason
            ) {
                dao.upsert(calibrated.toEntity())
                scheduler.schedule(calibrated)
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "daily-priority-calibration"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriorityCalibrationWorker>(
                24,
                TimeUnit.HOURS,
                2,
                TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
