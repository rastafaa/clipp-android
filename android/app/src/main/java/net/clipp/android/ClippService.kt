package net.clipp.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the sync core running: discovery, peer connections, and receiving.
 * Foreground type "connectedDevice" (syncing with the user's nearby devices),
 * which also avoids the daily time limit Android 15 puts on "dataSync".
 */
class ClippService : Service() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val peersListener: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0), type)
        running = true

        ClippNative.worker.execute { ClippNative.nativeStart() }
        ClippNative.peerListeners += peersListener
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        ClippNative.peerListeners -= peersListener
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        ClippNative.worker.execute { ClippNative.nativeStop() }
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val notify = { ClippNative.worker.execute { ClippNative.nativeOnNetworkChanged() } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { notify() }
            override fun onLost(network: Network) { notify() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { notify() }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun updateNotification() {
        ClippNative.worker.execute {
            val connected = ClippNative.peers().count { it.connected }
            val nm = getSystemService(NotificationManager::class.java)
            runCatching { nm.notify(NOTIFICATION_ID, buildNotification(connected)) }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Clipp keeps your clipboard in sync"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(connected: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val send = PendingIntent.getActivity(
            this, 1,
            Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (connected) {
            0 -> "Looking for your devices"
            1 -> "Connected to 1 device"
            else -> "Connected to $connected devices"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Clipp")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Send clipboard", send)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        @Volatile var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ClippService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClippService::class.java))
        }
    }
}
