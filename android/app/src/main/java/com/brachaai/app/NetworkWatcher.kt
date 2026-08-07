package com.brachaai.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Collapses a burst of connectivity callbacks into one trigger.
 *
 * Wi-Fi/cellular handover fires `onAvailable`/`onCapabilitiesChanged` several times within
 * seconds, and each firing would otherwise start a full sweep of the watch directory.
 *
 * Pure, so it is unit-tested without Android.
 */
class NetworkDebouncer(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastAcceptedMs: Long? = null

    /**
     * @return true when the caller should act on this event.
     *
     * Only *accepted* events move the window, so a network flapping faster than the window
     * cannot hold the sweep off forever. A clock that jumps backwards also passes, rather
     * than wedging shut until wall-clock time catches up.
     */
    @Synchronized
    fun accept(nowMs: Long): Boolean {
        val last = lastAcceptedMs
        val due = last == null || nowMs - last >= windowMs || nowMs < last
        if (due) lastAcceptedMs = nowMs
        return due
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
    }
}

/**
 * Calls [onNetworkAvailable] when the device gets a working internet connection.
 *
 * Gated on `NET_CAPABILITY_VALIDATED`, not mere connectivity: a captive-portal Wi-Fi is
 * "connected" while every request still fails, and sweeping then would burn attempts off
 * the give-up counter for no reason.
 *
 * Android delivers a callback for the already-connected network at registration time, so
 * [start] also covers service start and boot — no separate startup trigger is needed.
 *
 * A thin wrapper by design: everything worth testing is in [NetworkDebouncer].
 */
class NetworkWatcher(
    context: Context,
    private val onNetworkAvailable: () -> Unit
) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val debouncer = NetworkDebouncer()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = try {
                connectivityManager?.getNetworkCapabilities(network)
            } catch (e: Exception) {
                null
            }
            // onAvailable can precede validation, in which case onCapabilitiesChanged
            // follows with the validated flag set; both routes land here.
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                fire()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                fire()
            }
        }
    }

    private fun fire() {
        if (!debouncer.accept(System.currentTimeMillis())) return
        Log.i(TAG, "Validated network available; triggering a sweep")
        try {
            onNetworkAvailable()
        } catch (e: Exception) {
            Log.e(TAG, "Sweep trigger failed", e)
        }
    }

    fun start() {
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // Losing the trigger costs retries, not data — the recordings are still on disk
            // and the post-upload sweep still runs. It must not take the service down.
            Log.e(TAG, "Could not register the network callback", e)
        }
    }

    fun stop() {
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister the network callback", e)
        }
    }

    companion object {
        private const val TAG = "NetworkWatcher"
    }
}
