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
 * What is currently known about the device's connection, as far as a sweep cares.
 *
 * Three states, not two, because "we could not find out" is a genuinely different answer
 * from "there is no connection" and the two must lead to opposite decisions — see
 * [shouldSweepOnConnection].
 */
enum class ConnectionState {
    /** A default network is up and has passed Android's own end-to-end internet check. */
    VALIDATED,

    /**
     * There is provably nothing usable: no default network at all (airplane mode, no
     * signal), or one that failed validation (a captive portal). A determinate answer.
     */
    UNVALIDATED,

    /**
     * Connectivity could not be determined — no `ConnectivityManager`, the query threw, the
     * callback never registered, or the network vanished mid-query.
     */
    UNKNOWN
}

/**
 * Whether the periodic backstop sweep should run, given what is known about connectivity.
 *
 * Only [ConnectionState.UNVALIDATED] stops it. A sweep with no network burns one of every
 * pending recording's five attempts for nothing, so a phone offline for ~30 hours would see
 * five ticks and mark every pending recording `stuck` — kept and notified, but never retried
 * again. A long flight, a weekend without signal or an expired data plan all reach that, and
 * it is precisely the stranding this queue exists to prevent.
 *
 * [ConnectionState.UNKNOWN] deliberately sweeps. An unknown state must not silently disable
 * the only backstop there is: burning one attempt is recoverable — the recording survives and
 * four attempts remain — whereas never retrying at all is not.
 *
 * Pure, and top-level, so the decision is unit-testable without Android or a mocking
 * framework. Deliberately not applied to the other two sweep triggers: the [NetworkWatcher]
 * callback already fires only on a validated network, and `handleNewFile`'s follow-up sweep
 * is already gated on a `Completed` outcome.
 */
fun shouldSweepOnConnection(state: ConnectionState): Boolean = state != ConnectionState.UNVALIDATED

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

    /**
     * Set when [start] could not register the callback. Volatile because [connectionState]
     * is read from the service's coroutines, not from whichever thread called [start].
     */
    @Volatile
    private var registrationFailed = false

    /**
     * The device's connection right now, for callers that need to decide something *between*
     * transitions — currently only `CallMonitorService`'s 6-hour backstop sweep.
     *
     * Queried live rather than cached from [callback]. The callback fires only on a
     * transition, so a flag it set is exactly as stale as the time since the last one, and
     * the backstop's whole reason to exist is the phone that has sat on one network for
     * hours without a single transition.
     *
     * Never throws, and never reports a *determinate* answer it is not sure of.
     * `ACCESS_NETWORK_STATE` is a normal permission declared in the manifest, but a null
     * system service, a revoked permission or an OEM quirk must not take the periodic loop
     * down — each of those is [ConnectionState.UNKNOWN], which [shouldSweepOnConnection]
     * turns into "sweep anyway".
     */
    fun connectionState(): ConnectionState {
        if (registrationFailed) {
            // Registration fails for much the same reasons this query would (no
            // ConnectivityManager, no ACCESS_NETWORK_STATE), so treat the watcher as
            // unreliable as a whole rather than trusting one half of it.
            return ConnectionState.UNKNOWN
        }
        val manager = connectivityManager ?: return ConnectionState.UNKNOWN
        return try {
            // No default network at all is a real answer, not a missing one: the device is
            // offline. This is the case the backstop must skip.
            val active = manager.activeNetwork ?: return ConnectionState.UNVALIDATED
            // Non-null network but no capabilities means it went away between the two calls.
            // That is genuinely indeterminate, so it must not read as "offline".
            val capabilities = manager.getNetworkCapabilities(active) ?: return ConnectionState.UNKNOWN
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                ConnectionState.VALIDATED
            } else {
                // Connected but not validated — a captive portal. Requests still fail, so
                // sweeping would burn attempts exactly as being offline would.
                ConnectionState.UNVALIDATED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the current connection state; treating it as unknown", e)
            ConnectionState.UNKNOWN
        }
    }

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
            registrationFailed = true
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
