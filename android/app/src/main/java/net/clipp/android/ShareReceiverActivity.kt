package net.clipp.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Share-sheet target: sends shared text or an image to your devices. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ClippService.running && ClippNative.keyStatus().hasKey) {
            runCatching { ClippService.start(this) }
        }
        val intent = intent
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val isImage = intent.type?.startsWith("image/") == true
        ClippNative.worker.execute {
            val content = when {
                isImage && stream != null ->
                    ClipboardBridge.readImage(this, stream)?.let { (kind, bytes) -> ClipboardBridge.Content(kind, bytes, false) }
                !text.isNullOrEmpty() -> ClipboardBridge.Content(ClippNative.KIND_TEXT, text.encodeToByteArray(), false)
                else -> null
            }
            val message = content?.let { ClipboardBridge.send(it) } ?: "Nothing to send"
            runOnUiThread {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
