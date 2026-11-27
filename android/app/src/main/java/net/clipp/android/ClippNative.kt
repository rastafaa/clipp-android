package net.clipp.android

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Kotlin side of the JNI bridge (app/src/main/cpp/ClippJni.cpp).
 *
 * The `native*` functions call into the shared Clipp core. The `@JvmStatic`
 * callbacks below are invoked BY the core on its own threads; they only post
 * work and never call back into native synchronously (the core may be holding
 * a lock while it notifies).
 */
object ClippNative {
    const val KIND_TEXT = 0
    const val KIND_PNG = 1
    const val KIND_JPEG = 2

    private val main = Handler(Looper.getMainLooper())
    /** Single worker for native reads/writes that shouldn't run on the UI thread. */
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clipp-worker") }

    val activityListeners = CopyOnWriteArraySet<() -> Unit>()
    val peerListeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var onItemReceived: ((Long) -> Unit)? = null

    // ---- Core API ---------------------------------------------------------
    @JvmStatic external fun nativeInit(): Boolean
    @JvmStatic external fun nativeKeyStatus(): ByteArray
    @JvmStatic external fun nativePair(name: ByteArray, secret: ByteArray): ByteArray?
    @JvmStatic external fun nativeUnpair()
    @JvmStatic external fun nativeStart(): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeSend(kind: Int, data: ByteArray, sourceMarkedPrivate: Boolean): Long
    @JvmStatic external fun nativeItems(): ByteArray
    @JvmStatic external fun nativeItemFormat(id: Long): Int
    @JvmStatic external fun nativeItemData(id: Long): ByteArray?
    @JvmStatic external fun nativeItemIsPrivate(id: Long): Boolean
    @JvmStatic external fun nativeReshare(id: Long): Boolean
    @JvmStatic external fun nativeDelete(id: Long): Boolean
    @JvmStatic external fun nativeUndoDelete(): Long
    @JvmStatic external fun nativePeers(): ByteArray
    @JvmStatic external fun nativeMaskSecrets(): Boolean
    @JvmStatic external fun nativeSetMaskSecrets(mask: Boolean)
    @JvmStatic external fun nativeOnNetworkChanged()
    @JvmStatic external fun nativeMdnsRegistered(name: String)
    @JvmStatic external fun nativeMdnsResolved(name: String, ip: String, port: Int, keys: Array<String>, values: Array<String>)
    @JvmStatic external fun nativeMdnsLost(name: String)

    // ---- Typed wrappers ---------------------------------------------------
    data class KeyStatus(
        val networkName: String,
        val hasKey: Boolean,
        val fingerprint: String?,
        val deviceName: String,
    )

    data class Item(
        val id: Long,
        val device: String,
        val timestampMs: Long,
        val outgoing: Boolean,
        val kind: String,
        val sourceMarked: Boolean,
        val preview: String,
        /** Unmasked preview for private items (empty otherwise). */
        val revealed: String,
        val linkHost: String,
    ) {
        val canReveal get() = kind == "private" && revealed.isNotEmpty()
        val isImage get() = kind == "image"
        val isPrivate get() = kind == "private" || kind == "placeholder" || sourceMarked
        val hasContent get() = kind != "placeholder" && kind != "unsupported"
    }

    data class Peer(val id: String, val name: String, val os: Int, val connected: Boolean, val issue: Boolean)

    fun keyStatus(): KeyStatus {
        val o = JSONObject(nativeKeyStatus().decodeToString())
        return KeyStatus(
            networkName = o.optString("networkName"),
            hasKey = o.optBoolean("hasKey"),
            fingerprint = if (o.isNull("fingerprint")) null else o.getString("fingerprint"),
            deviceName = o.optString("deviceName"),
        )
    }

    /** Returns null on success, else an error message. */
    fun pair(name: String, secret: String): String? =
        nativePair(name.encodeToByteArray(), secret.encodeToByteArray())?.decodeToString()

    fun items(): List<Item> {
        val array = JSONArray(nativeItems().decodeToString())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Item(
                id = o.getLong("id"),
                device = o.optString("device"),
                timestampMs = o.optLong("ts"),
                outgoing = o.optBoolean("out"),
                kind = o.optString("kind"),
                sourceMarked = o.optBoolean("marked"),
                preview = o.optString("preview"),
                revealed = o.optString("revealed"),
                linkHost = o.optString("host"),
            )
        }.sortedByDescending { it.timestampMs }
    }

    fun peers(): List<Peer> {
        val array = JSONArray(nativePeers().decodeToString())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Peer(
                id = o.getString("id"),
                name = o.optString("name"),
                os = o.optInt("os"),
                connected = o.optBoolean("in") || o.optBoolean("out"),
                issue = o.optBoolean("issue"),
            )
        }
    }

    // ---- Callbacks from the core -------------------------------------------
    @JvmStatic fun onActivityChanged() {
        main.post { activityListeners.forEach { it() } }
    }

    @JvmStatic fun onPeersChanged() {
        main.post { peerListeners.forEach { it() } }
    }

    @JvmStatic fun onItemReceived(id: Long) {
        worker.execute { onItemReceived?.invoke(id) }
    }

    @JvmStatic fun mdnsPublish(name: String, port: Int, keys: Array<String>, values: Array<String>) {
        NsdDiscovery.publish(name, port, keys.zip(values).toMap())
    }

    @JvmStatic fun mdnsUnpublish() { NsdDiscovery.unpublish() }
    @JvmStatic fun mdnsBrowseStart() { NsdDiscovery.startBrowse() }
    @JvmStatic fun mdnsBrowseStop() { NsdDiscovery.stopBrowse() }
}
