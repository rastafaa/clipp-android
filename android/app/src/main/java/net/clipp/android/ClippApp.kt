package net.clipp.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.system.Os
import java.io.File

class ClippApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The core reads these at library load (g_settings is a static whose
        // constructor opens the settings file), so they must be set first.
        Os.setenv("XDG_CONFIG_HOME", filesDir.absolutePath, true)
        Os.setenv("CLIPP_STATE_DIR", File(filesDir, "state").absolutePath, true)
        Os.setenv("CLIPP_DEVICE_NAME", deviceName(), true)
        System.loadLibrary("clipp")

        NsdDiscovery.init(this)
        ClippNative.nativeInit()
        ClippNative.onItemReceived = { id ->
            if (Prefs.autoCopy(this)) ClipboardBridge.write(this, id)
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("clipp", Context.MODE_PRIVATE)
    fun autoCopy(context: Context) = prefs(context).getBoolean("autoCopy", true)
    fun setAutoCopy(context: Context, value: Boolean) = prefs(context).edit().putBoolean("autoCopy", value).apply()
}
