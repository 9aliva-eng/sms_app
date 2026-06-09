package com.smsapp

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically checks the agent status in Google Sheets.
 * If admin set status to "активен" – starts SmsService.
 * If admin set status to "остановлен" – stops SmsService.
 *
 * Runs every 5 minutes when app is in background.
 */
class AgentCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AgentCheckWorker"
        private const val WORK_NAME = "agent_check_worker"

        /**
         * Schedule periodic check every 5 minutes.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // нужен интернет
                .build()

            val request = PeriodicWorkRequestBuilder<AgentCheckWorker>(
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "AgentCheckWorker scheduled (every 5 min)")
        }

        /**
         * Cancel periodic check.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "AgentCheckWorker cancelled")
        }

        /**
         * Check if worker is scheduled (for UI).
         */
        fun isScheduled(context: Context): Boolean {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()
            return workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "AgentCheckWorker: checking agent status...")

        return withContext(Dispatchers.IO) {
            try {
                val prefs = PreferencesManager(applicationContext)

                if (!prefs.isConfigured()) {
                    Log.d(TAG, "Not configured, skipping")
                    return@withContext Result.success()
                }

                val repo = SheetsRepository(
                    prefs.sheetsId,
                    prefs.apiKey,
                    prefs.scriptUrl
                )

                // Получаем или создаём строку агента
                val rowResult = repo.ensureAgentRow(prefs.phoneName)
                if (rowResult.isFailure) {
                    Log.w(TAG, "Failed to get agent row: ${rowResult.exceptionOrNull()}")
                    return@withContext Result.retry()
                }

                val agentRowIndex = rowResult.getOrDefault(2)

                // Проверяем, не хочет ли админ запустить нас
                val isStopped = repo.isAgentStopped(agentRowIndex)
                val isServiceRunning = SmsServiceState.isRunning.value == true

                if (!isStopped && !isServiceRunning) {
                    // Админ поставил "активен" — запускаем сервис
                    Log.d(TAG, "AgentCheckWorker: admin set ACTIVE, starting SmsService")
                    applicationContext.startForegroundService(
                        SmsService.startIntent(applicationContext)
                    )
                } else if (isStopped && isServiceRunning) {
                    // Админ поставил "остановлен" — останавливаем сервис
                    Log.d(TAG, "AgentCheckWorker: admin set STOPPED, stopping SmsService")
                    applicationContext.startService(
                        SmsService.stopIntent(applicationContext)
                    )
                }

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "AgentCheckWorker error", e)
                Result.retry()
            }
        }
    }
}
