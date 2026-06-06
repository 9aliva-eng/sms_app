package com.smsapp

import com.google.gson.annotations.SerializedName

// ─── Google Sheets API response models ───────────────────────────────────────

data class SheetsResponse(
    @SerializedName("values") val values: List<List<String>>?
)

data class BatchUpdateRequest(
    @SerializedName("valueInputOption") val valueInputOption: String = "RAW",
    @SerializedName("data") val data: List<ValueRange>
)

data class ValueRange(
    @SerializedName("range") val range: String,
    @SerializedName("values") val values: List<List<String>>
)

// ─── Domain models ───────────────────────────────────────────────────────────

/**
 * Represents a single recipient row from the "Получатели" sheet.
 */
data class Recipient(
    val rowIndex: Int,          // 1-based row in sheet
    val phone: String,          // Column A  (e.g. 79991234567)
    val status: String,         // Column B  (ожидает / в_работе / отправлено / ошибка)
    val sentBy: String = "",    // Column C  (phoneName_SIM)
    val sentAt: String = ""     // Column D  (ISO timestamp)
) {
    companion object {
        const val STATUS_PENDING   = "ожидает"
        const val STATUS_IN_WORK   = "в_работе"
        const val STATUS_SENT      = "отправлено"
        const val STATUS_ERROR     = "ошибка"
    }
}

/**
 * Campaign settings from the "Настройки" sheet.
 * Row layout (column A = key, column B = value):
 *   1: smsText
 *   2: startTime   (HH:mm)
 *   3: endTime     (HH:mm)
 *   4: timezone    (UTC+9 – fixed)
 *   5: intervalMin (seconds)
 *   6: intervalMax (seconds)
 */
data class CampaignSettings(
    val smsText: String = "",
    val startTime: String = "09:00",   // HH:mm in UTC+9
    val endTime: String = "21:00",     // HH:mm in UTC+9
    val timezone: String = "UTC+9",
    val intervalMinSec: Int = 180,
    val intervalMaxSec: Int = 340
)

/**
 * Agent row from the "Агенты" sheet.
 */
data class Agent(
    val rowIndex: Int,
    val name: String,           // Column A
    val sim1Number: String,     // Column B
    val sim1Balance: Int,       // Column C  (manual)
    val sim2Number: String,     // Column D
    val sim2Balance: Int,       // Column E  (manual)
    val sim1Sent: Int,          // Column F  (app writes)
    val sim2Sent: Int,          // Column G  (app writes)
    val lastSeen: String,       // Column H  (timestamp)
    val agentStatus: String     // Column I  (активен / остановлен)
) {
    companion object {
        const val STATUS_ACTIVE  = "активен"
        const val STATUS_STOPPED = "остановлен"
    }
}

/**
 * Which SIM slots to use for sending.
 */
enum class SimMode {
    AUTO,   // both SIMs in parallel (default)
    SIM1,
    SIM2
}

/**
 * App-level preferences stored in SharedPreferences.
 */
data class AppPreferences(
    val isAdminMode: Boolean = false,
    val phoneName: String = "Phone1",
    val sheetsId: String = "",
    val sheetsApiKey: String = "",
    val simMode: SimMode = SimMode.AUTO
)

/**
 * Runtime state for one SIM slot.
 */
data class SimState(
    val simSlot: Int,       // 0 = SIM1, 1 = SIM2
    var balance: Int = 0,
    var sentToday: Int = 0,
    var active: Boolean = true,
    var lastSendTime: Long = 0L
)

/**
 * Events broadcast from SmsService to UI via LiveData / local broadcast.
 */
sealed class ServiceEvent {
    data class SmsSent(val phone: String, val simSlot: Int) : ServiceEvent()
    data class SmsError(val phone: String, val error: String) : ServiceEvent()
    object AllSimsExhausted : ServiceEvent()
    object OutsideTimeWindow : ServiceEvent()
    data class StatusUpdate(val sim1Sent: Int, val sim2Sent: Int) : ServiceEvent()
}
