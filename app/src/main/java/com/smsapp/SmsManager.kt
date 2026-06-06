package com.smsapp

import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Handles SMS sending with explicit SIM slot selection.
 * Requires SEND_SMS and READ_PHONE_STATE permissions.
 */
class SmsSender(private val context: Context) {

    // Track per-SIM hourly counts: simSlot -> list of send timestamps
    private val hourlyTimestamps = mutableMapOf<Int, MutableList<Long>>()

    /**
     * Returns the SmsManager for the given subscription ID.
     * subscriptionId = -1 means system default.
     */
    private fun getSmsManager(subscriptionId: Int): SmsManager {
        return if (subscriptionId == -1) {
            SmsManager.getDefault()
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    /**
     * Gets all available subscription IDs (SIM cards).
     * Returns list of [subscriptionId, simSlot] pairs.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    fun getAvailableSims(): List<Pair<Int, Int>> {
        return try {
            val subscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val infos = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            infos.map { it.subscriptionId to it.simSlotIndex }
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableSims error", e)
            emptyList()
        }
    }

    /**
     * Returns subscriptionId for the given SIM slot index (0-based).
     * Returns -1 if not found.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    fun getSubscriptionIdForSlot(slotIndex: Int): Int {
        return try {
            val subscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val infos = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            infos.firstOrNull { it.simSlotIndex == slotIndex }?.subscriptionId ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "getSubscriptionIdForSlot error", e)
            -1
        }
    }

    /**
     * Checks if the given SIM slot is within the hourly rate limit.
     */
    fun canSendOnSlot(simSlot: Int): Boolean {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3_600_000L
        val timestamps = hourlyTimestamps.getOrPut(simSlot) { mutableListOf() }
        // Remove old entries
        timestamps.removeAll { it < oneHourAgo }
        return timestamps.size < MAX_PER_HOUR
    }

    /**
     * Builds the SMS text with a unique character appended at the end.
     */
    fun buildSmsText(baseText: String): String {
        val uniqueChar = DECORATIVE_CHARS.random()
        return baseText + uniqueChar
    }

    /**
     * Sends an SMS through the specified SIM slot.
     *
     * @param phone        Destination phone number (e.g. "79991234567")
     * @param text         The SMS body
     * @param simSlot      0 for SIM1, 1 for SIM2
     * @return             true if the SMS was submitted without exception
     */
    @RequiresPermission(
        allOf = [            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_PHONE_STATE
        ]
    )
    fun sendSms(phone: String, text: String, simSlot: Int): Boolean {
        if (!canSendOnSlot(simSlot)) {
            Log.w(TAG, "Rate limit reached for SIM slot $simSlot")
            return false
        }

        // Сначала проверяем, есть ли SIM в этом слоте
        val subscriptionId = getSubscriptionIdForSlot(simSlot)
        if (subscriptionId == -1) {
            Log.e(TAG, "No SIM in slot $simSlot, aborting SMS send")
            return false
        }

        // Нормализуем номер: приводим к формату +7...
        val normalizedPhone = when {
            phone.startsWith("+") -> phone
            phone.startsWith("8") -> "+7" + phone.substring(1)
            phone.startsWith("7") -> "+$phone"
            else -> "+$phone"
        }

        return try {
            val manager = getSmsManager(subscriptionId)
            val smsc: String? = null

            val parts = manager.divideMessage(text)
            if (parts.size == 1) {
                manager.sendTextMessage(normalizedPhone, smsc, text, null, null)
            } else {
                manager.sendMultipartTextMessage(normalizedPhone, smsc, parts, null, null)
            }

            // Запоминаем отправку для лимита
            hourlyTimestamps
                .getOrPut(simSlot) { mutableListOf() }
                .add(System.currentTimeMillis())

            Log.d(
                TAG,
                "SMS sent to $normalizedPhone via SIM slot $simSlot (subscriptionId=$subscriptionId)"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendSms error for $phone on slot $simSlot", e)
            false
        }
    }

    /**
     * Returns how many SMS were sent in the last hour for a given slot.
     */
    fun getHourlySentCount(simSlot: Int): Int {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3_600_000L
        return hourlyTimestamps[simSlot]?.count { it >= oneHourAgo } ?: 0
    }

    companion object {
        private const val TAG = "SmsSender"
        const val MAX_PER_HOUR = 15

        // Unicode characters used to make each SMS unique
        private val DECORATIVE_CHARS = listOf(
            '\u200B', // Zero-width space
            '\u200C', // Zero-width non-joiner
            '\u200D', // Zero-width joiner
            '\u2060', // Word joiner
            '\uFEFF', // Zero-width no-break space
            '\u00AD', // Soft hyphen
            '\u034F', // Combining grapheme joiner
        )
    }
}
