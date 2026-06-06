package com.smsapp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Thin wrapper around SharedPreferences for app settings.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_ADMIN    = "is_admin_mode"
        private const val KEY_PHONE_NAME  = "phone_name"
        private const val KEY_SHEETS_ID   = "sheets_id"
        private const val KEY_API_KEY     = "api_key"
        private const val KEY_SIM_MODE    = "sim_mode"
        private const val KEY_SCRIPT_URL   = "script_url"
    }

    var isAdminMode: Boolean
        get()      = prefs.getBoolean(KEY_IS_ADMIN, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_ADMIN, value) }

    var phoneName: String
        get()      = prefs.getString(KEY_PHONE_NAME, "Phone1") ?: "Phone1"
        set(value) = prefs.edit { putString(KEY_PHONE_NAME, value) }

    var sheetsId: String
        get()      = prefs.getString(KEY_SHEETS_ID, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SHEETS_ID, value) }

    var apiKey: String
        get()      = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var scriptUrl: String
        get()      = prefs.getString(KEY_SCRIPT_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SCRIPT_URL, value) }

    var simMode: SimMode
        get()      = SimMode.valueOf(prefs.getString(KEY_SIM_MODE, SimMode.AUTO.name) ?: SimMode.AUTO.name)
        set(value) = prefs.edit { putString(KEY_SIM_MODE, value.name) }

    fun toAppPreferences() = AppPreferences(
        isAdminMode = isAdminMode,
        phoneName   = phoneName,
        sheetsId    = sheetsId,
        sheetsApiKey = apiKey,
        simMode     = simMode
    )

    fun isConfigured(): Boolean = sheetsId.isNotBlank() && apiKey.isNotBlank() && phoneName.isNotBlank() && scriptUrl.isNotBlank()
}
