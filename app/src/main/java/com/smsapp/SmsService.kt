package com.smsapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.random.Random

class SmsService : Service() {

    companion object {
        private const val TAG             = "SmsService"
        private const val CHANNEL_ID      = "sms_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.smsapp.ACTION_START"
        const val ACTION_STOP  = "com.smsapp.ACTION_STOP"

        fun startIntent(context: Context) =
            Intent(context, SmsService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, SmsService::class.java).apply { action = ACTION_STOP }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private lateinit var smsSender: SmsSender
    private lateinit var wakeLock: PowerManager.WakeLock
    private var agentRowIndex: Int = -1

    @Volatile private var sim1Sent = 0
    @Volatile private var sim2Sent = 0

    override fun onCreate() {
        super.onCreate()
        prefs     = PreferencesManager(this)
        smsSender = SmsSender(this)
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsApp::SmsWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else        -> startForegroundAndWork()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        SmsServiceState.isRunning.postValue(false)
        Log.d(TAG, "Service destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun startForegroundAndWork() {
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."))
        wakeLock.acquire(10 * 60 * 60 * 1000L)

        SmsServiceState.isRunning.postValue(true)
        SmsServiceState.sim1Sent.postValue(0)
        SmsServiceState.sim2Sent.postValue(0)

        serviceScope.launch {
            val repo = buildRepository() ?: run {
                postNotification("Ошибка: настройки не заданы")
                stopSelf()
                return@launch
            }

            val rowResult = repo.ensureAgentRow(prefs.phoneName)
            if (rowResult.isFailure) {
                postNotification("Ошибка подключения к Sheets")
                stopSelf()
                return@launch
            }
            agentRowIndex = rowResult.getOrDefault(2)

            // ── Запускаем наблюдатель статуса (каждые 10 секунд) ─────────
            val statusWatcher = launch { watchAdminCommands(repo) }

            // ── Проверяем, не остановлен ли агент из админки ────────────
            if (repo.isAgentStopped(agentRowIndex)) {
                postNotification("Остановлено администратором. Ожидание команды «Старт»...")
                // Ждём, пока админ не даст "Старт"
                waitForStartCommand(repo)
                // Если нас отменили — выходим
                if (!currentCoroutineContext().isActive) return@launch
            }

            val simMode = prefs.simMode
            when (simMode) {
                SimMode.AUTO -> {
                    launch { runSimLoop(repo, 0) }
                    launch { runSimLoop(repo, 1) }
                }
                SimMode.SIM1 -> launch { runSimLoop(repo, 0) }
                SimMode.SIM2 -> launch { runSimLoop(repo, 1) }
            }
        }
    }

    /**
     * Отдельная корутина — каждые 10 секунд проверяет статус в Sheets
     * и останавливает сервис, если админ нажал "Стоп".
     */
    private suspend fun watchAdminCommands(repo: SheetsRepository) {
        while (currentCoroutineContext().isActive) {
            delay(10_000L) // каждые 10 секунд
            if (agentRowIndex > 0 && repo.isAgentStopped(agentRowIndex)) {
                Log.d(TAG, "watchAdminCommands: stop flag detected")
                postNotification("Остановлено администратором")
                stopSelf()
                return
            }
        }
    }

    /**
     * Ожидание команды "Старт" от администратора.
     * Проверяет статус каждые 30 секунд.
     */
    private suspend fun waitForStartCommand(repo: SheetsRepository) {
        postNotification("Ожидание команды «Старт»...")
        while (currentCoroutineContext().isActive) {
            delay(30_000L)
            if (agentRowIndex > 0 && !repo.isAgentStopped(agentRowIndex)) {
                // Статус изменился на "активен" — запускаем рассылку
                postNotification("Получена команда «Старт» от администратора")
                Log.d(TAG, "waitForStartCommand: start command received")
                return
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Остальной код без изменений
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRepository(): SheetsRepository? {
        val id        = prefs.sheetsId
        val key       = prefs.apiKey
        val scriptUrl = prefs.scriptUrl
        if (id.isBlank() || key.isBlank() || scriptUrl.isBlank()) return null
        return SheetsRepository(id, key, scriptUrl)
    }

    private suspend fun runSimLoop(repo: SheetsRepository, simSlot: Int) {
        Log.d(TAG, "SIM $simSlot loop started")

        if (simSlot == 0) SmsServiceState.sim1Active.postValue(true)
        else              SmsServiceState.sim2Active.postValue(true)

        val subId = smsSender.getSubscriptionIdForSlot(simSlot)
        if (subId == -1) {
            Log.w(TAG, "No SIM in slot $simSlot, stopping loop for this slot")
            if (simSlot == 0) SmsServiceState.sim1Active.postValue(false)
            else              SmsServiceState.sim2Active.postValue(false)
            checkAllSimsExhausted()
            return
        }

        var balance = if (agentRowIndex > 0) repo.getSimBalance(agentRowIndex, simSlot) else 0
        val senderTag = "${prefs.phoneName}_SIM${simSlot + 1}"

        while (currentCoroutineContext().isActive) {
            // ── Check stop flag from Sheets ───────────────────────────────
            if (agentRowIndex > 0 && repo.isAgentStopped(agentRowIndex)) {
                Log.d(TAG, "SIM $simSlot: stop flag set in Sheets")
                postNotification("Остановлено администратором")
                break
            }

            // ── Check balance ─────────────────────────────────────────────
            if (balance <= 0) {
                Log.d(TAG, "SIM $simSlot: balance exhausted")
                checkAllSimsExhausted()
                break
            }

            // ── Read settings ─────────────────────────────────────────────
            val settingsResult = repo.getCampaignSettings()
            val settings = settingsResult.getOrNull()
            if (settings == null) {
                Log.w(TAG, "SIM $simSlot: failed to fetch settings, retrying in 60s")
                delay(60_000L)
                continue
            }

            // ── Time window check ─────────────────────────────────────────
            if (!TimeManager.isWithinWindow(settings.startTime, settings.endTime, settings.timezone)) {
                val waitMs = TimeManager.millisUntilWindowOpen(settings.startTime, settings.timezone)
                Log.d(TAG, "SIM $simSlot: outside window, sleeping ${waitMs / 60000}m")
                postNotification("Ожидание окна рассылки (с ${settings.startTime})")
                SmsServiceState.event.postValue(ServiceEvent.OutsideTimeWindow)
                delay(waitMs.coerceAtMost(60_000L))
                continue
            }

            // ── Rate limit check ──────────────────────────────────────────
            if (!smsSender.canSendOnSlot(simSlot)) {
                Log.d(TAG, "SIM $simSlot: rate limit reached, sleeping 5m")
                delay(5 * 60_000L)
                continue
            }

            // ── Claim next recipient ──────────────────────────────────────
            val recipientResult = repo.claimNextRecipient(senderTag)
            val recipient = recipientResult.getOrNull()
            if (recipient == null) {
                if (recipientResult.isFailure) {
                    Log.w(TAG, "SIM $simSlot: claim failed, retry in 30s")
                    delay(30_000L)
                } else {
                    Log.d(TAG, "SIM $simSlot: no more pending recipients")
                    postNotification("Рассылка завершена — номера закончились")
                    break
                }
                continue
            }

            // ── Build and send SMS ────────────────────────────────────────
            val smsText = smsSender.buildSmsText(settings.smsText)
            val success = try {
                @Suppress("MissingPermission")
                smsSender.sendSms(recipient.phone, smsText, simSlot)
            } catch (e: SecurityException) {
                Log.e(TAG, "SMS permission denied", e)
                false
            }

            val status    = if (success) Recipient.STATUS_SENT else Recipient.STATUS_ERROR
            val timestamp = TimeManager.nowTimestamp()

            repo.markRecipientResult(recipient.rowIndex, status, senderTag, timestamp)

            if (success) {
                balance--
                if (simSlot == 0) sim1Sent++ else sim2Sent++
                SmsServiceState.sim1Sent.postValue(sim1Sent)
                SmsServiceState.sim2Sent.postValue(sim2Sent)
                SmsServiceState.event.postValue(ServiceEvent.SmsSent(recipient.phone, simSlot))

                if (agentRowIndex > 0) {
                    repo.updateAgentStats(agentRowIndex, sim1Sent, sim2Sent)
                }

                val totalSent = sim1Sent + sim2Sent
                postNotification("Отправлено сегодня: $totalSent СМС")
            } else {
                SmsServiceState.event.postValue(ServiceEvent.SmsError(recipient.phone, "Ошибка отправки"))
            }

            val delayMs = Random.nextLong(
                settings.intervalMinSec * 1000L,
                settings.intervalMaxSec * 1000L
            )
            Log.d(TAG, "SIM $simSlot: waiting ${delayMs / 1000}s before next SMS")
            delay(delayMs)
        }

        if (simSlot == 0) SmsServiceState.sim1Active.postValue(false)
        else              SmsServiceState.sim2Active.postValue(false)

        Log.d(TAG, "SIM $simSlot loop ended")
    }

    private fun checkAllSimsExhausted() {
        val sim1 = SmsServiceState.sim1Active.value ?: true
        val sim2 = SmsServiceState.sim2Active.value ?: true
        if (!sim1 && !sim2) {
            SmsServiceState.event.postValue(ServiceEvent.AllSimsExhausted)
            postNotification("Все SIM исчерпаны")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Рассылка",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновая работа SMS рассылки"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Рассылка")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun postNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
        SmsServiceState.statusText.postValue(text)
    }
}
