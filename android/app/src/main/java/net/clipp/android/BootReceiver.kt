package net.clipp.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts background sync after a reboot or an app update, if paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (ClippNative.keyStatus().hasKey) {
            runCatching { ClippService.start(context) }
        }
    }
}
