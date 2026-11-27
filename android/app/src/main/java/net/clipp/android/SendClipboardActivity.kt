package net.clipp.android

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity behind the Quick Settings tile and the notification action.
 * Android only lets the focused app read the clipboard, so this briefly takes
 * focus, reads it, sends it, and closes.
 */
class SendClipboardActivity : Activity() {
    private var handled = false

    override fun onDestroy() {
        SyncControl.release(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncControl.acquire(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val content = ClipboardBridge.read(this)
        if (content == null) {
            Toast.makeText(applicationContext, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ClippNative.worker.execute {
            val message = ClipboardBridge.send(content)
            runOnUiThread {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
