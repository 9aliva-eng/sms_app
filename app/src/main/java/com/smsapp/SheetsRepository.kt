package com.smsapp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * All Google Sheets read/write operations.
 *
 * Sheet names (as defined in the spec):
 *   "Получатели"  – recipient list
 *   "Настройки"   – campaign settings
 *   "Агенты"      – agent status
 */
class SheetsRepository(
    private val sheetsId: String,
    private val apiKey: String,
    private val scriptUrl: String = ""
) {
    companion object {
        private const val TAG = "SheetsRepository"
        private const val BASE_URL = "https://sheets.googleapis.com/v4/spreadsheets/"

        // Sheet tab names
        const val SHEET_RECIPIENTS = "Получатели"
        const val SHEET_SETTINGS   = "Настройки"
        const val SHEET_AGENTS     = "Агенты"
    }

    // Mutex to prevent concurrent recipient claims
    private val claimMutex = Mutex()

    private val httpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * POST request to Apps Script URL with JSON body.
     * Returns true if successful.
     */
    private fun scriptPost(request: ScriptWriteRequest): Boolean {
        if (scriptUrl.isBlank()) return false
        return try {
            val json = gson.toJson(request)
            val body = json.toRequestBody(jsonMediaType)
            val req  = Request.Builder()
                .url(scriptUrl)
                .post(body)
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val respBody = resp.body?.string()
            Log.d(TAG, "scriptPost: HTTP $code body=$respBody")
            resp.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "scriptPost error", e)
            false
        }
    }

    private val api: SheetsApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val gson = GsonBuilder().setLenient().create()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(SheetsApiService::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Campaign Settings
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads campaign settings from "Настройки" sheet.
     * Expected layout (row: A=key, B=value):
     *   Row 2: SMS text
     *   Row 3: start time (HH:mm)
     *   Row 4: end time (HH:mm)
     *   Row 5: timezone (UTC+9)
     *   Row 6: interval min (seconds)
     *   Row 7: interval max (seconds)
     */
    suspend fun getCampaignSettings(): Result<CampaignSettings> = withContext(Dispatchers.IO) {
        try {
            val range = "$SHEET_SETTINGS!A2:B7"
            val response = api.getValues(sheetsId, range, apiKey)
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}"))
            }
            val rows = response.body()?.values ?: emptyList()
            fun cell(row: Int, col: Int = 1): String =
                rows.getOrNull(row)?.getOrNull(col)?.trim() ?: ""

            val settings = CampaignSettings(
                smsText      = cell(0),
                startTime    = cell(1).ifEmpty { "09:00" },
                endTime      = cell(2).ifEmpty { "21:00" },
                timezone     = cell(3).ifEmpty { "UTC+9" },
                intervalMinSec = cell(4).toIntOrNull() ?: 180,
                intervalMaxSec = cell(5).toIntOrNull() ?: 340
            )
            Result.success(settings)
        } catch (e: Exception) {
            Log.e(TAG, "getCampaignSettings error", e)
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipients
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Claims the next pending recipient atomically.
     * Returns null if no pending recipients exist.
     */
    suspend fun claimNextRecipient(senderTag: String): Result<Recipient?> =
        withContext(Dispatchers.IO) {
            claimMutex.withLock {
                try {
                    // Read all recipients
                    val range = "$SHEET_RECIPIENTS!A2:D"
                    val response = api.getValues(sheetsId, range, apiKey)
                    if (!response.isSuccessful) {
                        return@withLock Result.failure(
                            Exception("HTTP ${response.code()}")
                        )
                    }
                    val rows = response.body()?.values ?: emptyList()

                    // Find first row with empty or "ожидает" status
                    val idx = rows.indexOfFirst { row ->
                        val status = row.getOrNull(1)?.trim() ?: ""
                        status == Recipient.STATUS_PENDING || status.isEmpty()
                    }
                    if (idx == -1) return@withLock Result.success(null)

                    val sheetRow = idx + 2  // 1-based, offset by header
                    val phone = rows[idx].getOrNull(0)?.trim() ?: return@withLock Result.success(null)

                    // Mark as "в_работе" via Apps Script
                    if (scriptUrl.isBlank()) {
                        return@withLock Result.failure(Exception("Script URL not configured"))
                    }
                    val ok = scriptPost(
                        ScriptWriteRequest(
                            sheet  = SHEET_RECIPIENTS,
                            range  = "B$sheetRow:C$sheetRow",
                            values = listOf(listOf(Recipient.STATUS_IN_WORK, senderTag))
                        )
                    )
                    if (!ok) {
                        return@withLock Result.failure(Exception("Claim update failed"))
                    }

                    Result.success(
                        Recipient(
                            rowIndex = sheetRow,
                            phone    = phone,
                            status   = Recipient.STATUS_IN_WORK,
                            sentBy   = senderTag
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "claimNextRecipient error", e)
                    Result.failure(e)
                }
            }
        }

    /**
     * Writes the final result (sent / error) back to the recipient row.
     */
    suspend fun markRecipientResult(
        rowIndex: Int,
        status: String,
        sentBy: String,
        timestamp: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (scriptUrl.isBlank()) return@withContext Result.failure(Exception("Script URL not configured"))
            val ok = scriptPost(
                ScriptWriteRequest(
                    sheet  = SHEET_RECIPIENTS,
                    range  = "B$rowIndex:D$rowIndex",
                    values = listOf(listOf(status, sentBy, timestamp))
                )
            )
            if (!ok) {
                return@withContext Result.failure(Exception("markRecipientResult failed"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "markRecipientResult error", e)
            Result.failure(e)
        }
    }

    /**
     * Returns count of recipients by status.
     */
    suspend fun getRecipientStats(): Map<String, Int> = withContext(Dispatchers.IO) {
        try {
            val range    = "$SHEET_RECIPIENTS!B2:B"
            val response = api.getValues(sheetsId, range, apiKey)
            val rows     = response.body()?.values ?: emptyList()
            rows.groupingBy { it.getOrNull(0)?.trim() ?: "" }.eachCount()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Agents
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads all agent rows.
     */
    suspend fun getAllAgents(): Result<List<Agent>> = withContext(Dispatchers.IO) {
        try {
            val range    = "$SHEET_AGENTS!A2:I"
            val response = api.getValues(sheetsId, range, apiKey)
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code()}"))
            }
            val rows = response.body()?.values ?: emptyList()
            val agents = rows.mapIndexedNotNull { idx, row ->
                val name = row.getOrNull(0)?.trim() ?: return@mapIndexedNotNull null
                Agent(
                    rowIndex    = idx + 2,
                    name        = name,
                    sim1Number  = row.getOrNull(1)?.trim() ?: "",
                    sim1Balance = row.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
                    sim2Number  = row.getOrNull(3)?.trim() ?: "",
                    sim2Balance = row.getOrNull(4)?.trim()?.toIntOrNull() ?: 0,
                    sim1Sent    = row.getOrNull(5)?.trim()?.toIntOrNull() ?: 0,
                    sim2Sent    = row.getOrNull(6)?.trim()?.toIntOrNull() ?: 0,
                    lastSeen    = row.getOrNull(7)?.trim() ?: "",
                    agentStatus = row.getOrNull(8)?.trim() ?: Agent.STATUS_STOPPED
                )
            }
            Result.success(agents)
        } catch (e: Exception) {
            Log.e(TAG, "getAllAgents error", e)
            Result.failure(e)
        }
    }

    /**
     * Finds or creates the row for this phone and returns its row index.
     * If no row with [phoneName] exists, appends a new one.
     */
    suspend fun ensureAgentRow(phoneName: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val agentsResult = getAllAgents()
            if (agentsResult.isFailure) return@withContext Result.failure(agentsResult.exceptionOrNull()!!)

            val agents = agentsResult.getOrDefault(emptyList())
            val existing = agents.firstOrNull { it.name == phoneName }
            if (existing != null) return@withContext Result.success(existing.rowIndex)

            // Append new row via Apps Script
            val newRow = agents.size + 2  // after header
            if (scriptUrl.isBlank()) return@withContext Result.failure(Exception("Script URL not configured"))
            val ok = scriptPost(
                ScriptWriteRequest(
                    sheet  = SHEET_AGENTS,
                    range  = "A$newRow:I$newRow",
                    values = listOf(listOf(phoneName, "", "0", "", "0", "0", "0", TimeManager.nowTimestamp(), Agent.STATUS_ACTIVE))
                )
            )
            if (!ok) {
                return@withContext Result.failure(Exception("ensureAgentRow write failed"))
            }
            Result.success(newRow)
        } catch (e: Exception) {
            Log.e(TAG, "ensureAgentRow error", e)
            Result.failure(e)
        }
    }

    /**
     * Updates the agent's sent counters and last-seen timestamp.
     */
    suspend fun updateAgentStats(
        rowIndex: Int,
        sim1Sent: Int,
        sim2Sent: Int,
        agentStatus: String = Agent.STATUS_ACTIVE
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val timestamp = TimeManager.nowTimestamp()
            if (scriptUrl.isBlank()) return@withContext Result.failure(Exception("Script URL not configured"))
            // Update F (sim1Sent), G (sim2Sent), H (lastSeen), I (status)
            val ok = scriptPost(
                ScriptWriteRequest(
                    sheet  = SHEET_AGENTS,
                    range  = "F$rowIndex:I$rowIndex",
                    values = listOf(listOf(sim1Sent.toString(), sim2Sent.toString(), timestamp, agentStatus))
                )
            )
            if (!ok) {
                return@withContext Result.failure(Exception("updateAgentStats failed"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateAgentStats error", e)
            Result.failure(e)
        }
    }

    /**
     * Reads the stop flag for this agent (Column I = "остановлен" means stop).
     */
    suspend fun isAgentStopped(rowIndex: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val range    = "$SHEET_AGENTS!I$rowIndex"
            val response = api.getValues(sheetsId, range, apiKey)
            val value    = response.body()?.values?.firstOrNull()?.firstOrNull()?.trim()
            value == Agent.STATUS_STOPPED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads remaining SMS balance for a SIM from the sheet.
     * simIndex: 0 = SIM1 (col C), 1 = SIM2 (col E)
     */
    suspend fun getSimBalance(rowIndex: Int, simIndex: Int): Int = withContext(Dispatchers.IO) {
        try {
            val col      = if (simIndex == 0) "C" else "E"
            val range    = "$SHEET_AGENTS!$col$rowIndex"
            val response = api.getValues(sheetsId, range, apiKey)
            response.body()?.values?.firstOrNull()?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
