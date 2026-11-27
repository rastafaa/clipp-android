package net.clipp.android

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Decides when the sync core runs.
 *
 * Background sync ON (default): ClippService runs all the time, with its notification.
 * Background sync OFF: the core runs only while something needs it (the app is
 * open, or a send from the tile/share sheet is in flight) and stops shortly after.
 * On reconnect the core asks peers for anything it missed, so history catches up
 * as soon as the app is opened.
 */
object SyncControl {
    private const val IDLE_STOP_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())
    private var users = 0
    val inUse get() = users > 0
    private val stopRunnable = Runnable { ClippNative.worker.execute { ClippNative.nativeStop() } }

    fun backgroundEnabled(context: Context) = Prefs.backgroundSync(context)

    /** Applies the current setting: starts or stops the always-on service. */
    fun apply(context: Context) {
        if (!ClippNative.keyStatus().hasKey) return
        if (backgroundEnabled(context)) {
            main.removeCallbacks(stopRunnable)
            if (!ClippService.running) runCatching { ClippService.start(context) }
        } else {
            if (ClippService.running) ClippService.stop(context)
            if (users > 0) ClippNative.worker.execute { ClippNative.nativeStart() } else scheduleStop()
        }
    }

    /** Something needs the core running (call on the main thread). */
    fun acquire(context: Context) {
        users++
        main.removeCallbacks(stopRunnable)
        if (backgroundEnabled(context)) {
            if (!ClippService.running && ClippNative.keyStatus().hasKey) runCatching { ClippService.start(context) }
        } else {
            ClippNative.worker.execute { ClippNative.nativeStart() }
        }
    }

    /** Pairs with [acquire] (call on the main thread). */
    fun release(context: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && !backgroundEnabled(context)) scheduleStop()
    }

    private fun scheduleStop() {
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, IDLE_STOP_MS)
    }

    /** Waits (off the main thread) up to [timeoutMs] for at least one connected peer. */
    fun awaitPeers(timeoutMs: Long = 4_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ClippNative.peers().any { it.connected }) {
                Thread.sleep(300)  // let the remaining peers finish their handshakes
                return
            }
            Thread.sleep(150)
        }
    }
}
