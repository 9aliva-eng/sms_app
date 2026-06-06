package com.smsapp

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Handles all time-related logic.
 * Timezone is read from Google Sheets (Настройки, row 5).
 * Default: UTC+9 (Blagoveshchensk / Asia/Yakutsk).
 */
object TimeManager {

    private val DEFAULT_ZONE = ZoneId.of("UTC+9")
    private val TIME_FMT  = DateTimeFormatter.ofPattern("HH:mm")
    val TIMESTAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Parses timezone string from Sheets (e.g. "Asia/Yakutsk", "UTC+9").
     * Falls back to UTC+9 if invalid.
     */
    private fun parseZone(timezone: String): ZoneId {
        return try {
            ZoneId.of(timezone.trim())
        } catch (e: Exception) {
            DEFAULT_ZONE
        }
    }

    /**
     * Returns true if the current moment (in recipient timezone) falls within [startTime, endTime].
     * Both times are "HH:mm" strings. timezone from Sheets settings.
     */
    fun isWithinWindow(startTime: String, endTime: String, timezone: String = "UTC+9"): Boolean {
        return try {
            val zone     = parseZone(timezone)
            val now      = ZonedDateTime.now(zone)
            val nowTime  = now.toLocalTime()
            val start    = LocalTime.parse(startTime, TIME_FMT)
            val end      = LocalTime.parse(endTime, TIME_FMT)

            if (start <= end) {
                nowTime in start..end
            } else {
                // overnight window (e.g. 22:00 – 06:00)
                nowTime >= start || nowTime <= end
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns current timestamp string in recipient timezone for writing to Sheets.
     */
    fun nowTimestamp(timezone: String = "UTC+9"): String {
        val zone = parseZone(timezone)
        val now  = ZonedDateTime.now(zone)
        return now.format(TIMESTAMP_FMT)
    }

    /**
     * Returns current time in recipient timezone formatted as HH:mm.
     */
    fun nowTimeString(timezone: String = "UTC+9"): String {
        val zone = parseZone(timezone)
        val now  = ZonedDateTime.now(zone)
        return now.toLocalTime().format(TIME_FMT)
    }

    /**
     * Milliseconds until the next start of the sending window.
     */
    fun millisUntilWindowOpen(startTime: String, timezone: String = "UTC+9"): Long {
        return try {
            val zone     = parseZone(timezone)
            val now      = ZonedDateTime.now(zone)
            val start    = LocalTime.parse(startTime, TIME_FMT)
            var next     = now.withHour(start.hour).withMinute(start.minute).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            java.time.Duration.between(now, next).toMillis()
        } catch (e: Exception) {
            3_600_000L // default: 1 hour
        }
    }
}
