package com.smsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the SMS service after device reboot if the app was previously running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = PreferencesManager(context)
            // Only auto-restart in agent mode and if configured
            if (!prefs.isAdminMode && prefs.isConfigured()) {
                context.startForegroundService(SmsService.startIntent(context))
            }
        }
    }
}
