package net.clipp.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.Executor

/**
 * DNS-SD for the core's MDNSDiscovery (MDNSDiscovery_Android.cpp), on top of
 * NsdManager. The native side builds/decrypts the TXT payload; this class only
 * registers, browses and resolves `_clipp._tcp`, and reports results back.
 *
 * Every NsdManager callback is re-posted to one HandlerThread, so state here is
 * single-threaded and native is never re-entered from inside a native call.
 */
object NsdDiscovery {
    private const val TAG = "ClippNsd"
    private const val SERVICE_TYPE = "_clipp._tcp"

    private lateinit var nsd: NsdManager
    private val thread = HandlerThread("clipp-nsd").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    // API 34+: one live info callback per discovered instance (also reports updates).
    private val infoCallbacks = mutableMapOf<String, Any>()
    // Older APIs: resolveService allows one resolve at a time, so queue them.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun init(context: Context) {
        nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    fun publish(name: String, port: Int, txt: Map<String, String>) { handler.post { publishNow(name, port, txt) } }

    private fun publishNow(name: String, port: Int, txt: Map<String, String>) {
        unpublishNow()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = handler.post {
                Log.i(TAG, "Registered as ${info.serviceName}")
                ClippNative.nativeMdnsRegistered(info.serviceName)
            }.let { }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService threw", it); registration = null }
    }

    fun unpublish() { handler.post { unpublishNow() } }

    private fun unpublishNow() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    fun startBrowse() { handler.post { startBrowseNow() } }

    private fun startBrowseNow() {
        stopBrowseNow()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = handler.post { onFound(info) }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = handler.post { onLost(info.serviceName) }.let { }
            override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "Browsing $serviceType") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Browse failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discoverServices threw", it); discovery = null }
    }

    fun stopBrowse() { handler.post { stopBrowseNow() } }

    private fun stopBrowseNow() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.values.forEach { cb ->
                runCatching { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
    }

    private fun onFound(info: NsdServiceInfo) {
        if (!info.serviceName.startsWith("clipp-")) return
        if (Build.VERSION.SDK_INT >= 34) {
            if (infoCallbacks.containsKey(info.serviceName)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(updated: NsdServiceInfo) = report(updated)
                override fun onServiceLost() = onLost(info.serviceName)
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                    infoCallbacks.remove(info.serviceName)
                }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks[info.serviceName] = callback
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
                .onFailure { infoCallbacks.remove(info.serviceName) }
        } else {
            resolveQueue.addLast(info)
            pumpResolveQueue()
        }
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveQueue() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = handler.post {
                resolving = false
                report(info)
                pumpResolveQueue()
            }.let { }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = handler.post {
                resolving = false
                pumpResolveQueue()
            }.let { }
        })
    }

    private fun onLost(name: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.remove(name)?.let { cb ->
                runCatching { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        ClippNative.nativeMdnsLost(name)
    }

    @Suppress("DEPRECATION")
    private fun report(info: NsdServiceInfo) {
        // The core connects over IPv4 only (same as the desktop and iOS builds).
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val ip = addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
        val keys = mutableListOf<String>()
        val values = mutableListOf<String>()
        info.attributes.forEach { (k, v) ->
            keys += k
            values += v?.decodeToString() ?: ""
        }
        ClippNative.nativeMdnsResolved(info.serviceName, ip, info.port, keys.toTypedArray(), values.toTypedArray())
    }
}
