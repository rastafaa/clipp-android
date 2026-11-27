package net.clipp.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Moves content between Clipp items and the Android system clipboard. */
object ClipboardBridge {
    // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the literal works on every version.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private val main = Handler(Looper.getMainLooper())

    class Content(val kind: Int, val data: ByteArray, val sensitive: Boolean)

    /** Writes a Clipp activity item to the system clipboard. Call off the main thread. */
    fun write(context: Context, id: Long): Boolean {
        val format = ClippNative.nativeItemFormat(id)
        val data = ClippNative.nativeItemData(id) ?: return false
        val sensitive = ClippNative.nativeItemIsPrivate(id)
        val clip = when (format) {
            ClippNative.KIND_TEXT -> ClipData.newPlainText("Clipp", data.decodeToString())
            ClippNative.KIND_PNG, ClippNative.KIND_JPEG -> {
                val uri = cacheImage(context, id, data, if (format == ClippNative.KIND_PNG) "png" else "jpg")
                ClipData.newUri(context.contentResolver, "Clipp image", uri)
            }
            else -> return false
        }
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        main.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            runCatching { cm.setPrimaryClip(clip) }
        }
        return true
    }

    private fun cacheImage(context: Context, id: Long, data: ByteArray, ext: String): Uri {
        val dir = File(context.cacheDir, "clips").apply { mkdirs() }
        // Keep only the latest few: apps that pasted earlier images may still read them briefly.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val file = File(dir, "clip-$id.$ext")
        file.writeBytes(data)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Reads the system clipboard. Only works while one of our activities has focus. */
    fun read(context: Context): Content? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val sensitive = clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
        val uri = item.uri
        if (uri != null && (clip.description.hasMimeType("image/*") ||
                context.contentResolver.getType(uri)?.startsWith("image/") == true)) {
            readImage(context, uri)?.let { (kind, bytes) -> return Content(kind, bytes, sensitive) }
        }
        val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return null
        return Content(ClippNative.KIND_TEXT, text.encodeToByteArray(), sensitive)
    }

    /** Image bytes as PNG or JPEG (other formats are re-encoded as PNG). */
    fun readImage(context: Context, uri: Uri): Pair<Int, ByteArray>? = runCatching {
        val type = context.contentResolver.getType(uri) ?: ""
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        when (type) {
            "image/png" -> ClippNative.KIND_PNG to bytes
            "image/jpeg", "image/jpg" -> ClippNative.KIND_JPEG to bytes
            else -> {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                ClippNative.KIND_PNG to out.toByteArray()
            }
        }
    }.getOrNull()

    /** Sends content and returns a short result message for a toast. */
    fun send(content: Content): String {
        // When the core was just started (background sync off), give peers a moment to connect.
        if (ClippNative.peers().none { it.connected }) SyncControl.awaitPeers()
        val id = ClippNative.nativeSend(content.kind, content.data, content.sensitive)
        return when {
            id == -1L -> "Open Clipp and join your group first"
            id == 0L -> "Couldn't send that"
            else -> {
                val connected = ClippNative.peers().count { it.connected }
                when (connected) {
                    0 -> "Saved, but no devices are connected right now"
                    1 -> "Sent to 1 device"
                    else -> "Sent to $connected devices"
                }
            }
        }
    }
}
