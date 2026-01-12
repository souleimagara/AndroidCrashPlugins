package com.crashreporter.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reachability Tracker
 * Tracks network connectivity changes to detect if crashes occur after network loss
 */
class ReachabilityTracker(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkChanges = ConcurrentLinkedQueue<NetworkChange>()
    private var isRegistered = false

    companion object {
        private const val MAX_CHANGES = 20
    }

    /**
     * Start tracking network changes
     */
    fun startTracking() {
        if (isRegistered) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }

            isRegistered = true

            // Log initial state
            logCurrentNetworkState()

            android.util.Log.i("ReachabilityTracker", "✅ Network reachability tracking started")
        } catch (e: Exception) {
            android.util.Log.e("ReachabilityTracker", "Failed to start tracking", e)
        }
    }

    /**
     * Stop tracking network changes
     */
    fun stopTracking() {
        if (!isRegistered) {
            return
        }

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            android.util.Log.i("ReachabilityTracker", "Network reachability tracking stopped")
        } catch (e: Exception) {
            android.util.Log.e("ReachabilityTracker", "Failed to stop tracking", e)
        }
    }

    /**
     * Network callback to track changes
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val networkType = getNetworkType(network)
            val change = NetworkChange(
                timestamp = System.currentTimeMillis(),
                event = "AVAILABLE",
                networkType = networkType,
                isConnected = true
            )
            addChange(change)
            android.util.Log.d("ReachabilityTracker", "📶 Network available: $networkType")
        }

        override fun onLost(network: Network) {
            val change = NetworkChange(
                timestamp = System.currentTimeMillis(),
                event = "LOST",
                networkType = "NONE",
                isConnected = false
            )
            addChange(change)
            android.util.Log.w("ReachabilityTracker", "❌ Network lost")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val networkType = getNetworkType(capabilities)
            val change = NetworkChange(
                timestamp = System.currentTimeMillis(),
                event = "CHANGED",
                networkType = networkType,
                isConnected = true
            )
            addChange(change)
            android.util.Log.d("ReachabilityTracker", "🔄 Network changed: $networkType")
        }
    }

    /**
     * Get network type from capabilities
     */
    private fun getNetworkType(network: Network): String {
        return try {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            getNetworkType(capabilities)
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun getNetworkType(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "NONE"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            else -> "OTHER"
        }
    }

    /**
     * Log current network state
     */
    private fun logCurrentNetworkState() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val networkType = getNetworkType(capabilities)
                val isConnected = capabilities != null

                val change = NetworkChange(
                    timestamp = System.currentTimeMillis(),
                    event = "INITIAL",
                    networkType = networkType,
                    isConnected = isConnected
                )
                addChange(change)
            }
        } catch (e: Exception) {
            android.util.Log.e("ReachabilityTracker", "Error logging initial state", e)
        }
    }

    /**
     * Add network change to history
     */
    private fun addChange(change: NetworkChange) {
        networkChanges.add(change)

        // Keep only last MAX_CHANGES
        while (networkChanges.size > MAX_CHANGES) {
            networkChanges.poll()
        }
    }

    /**
     * Get all network changes
     */
    fun getNetworkChanges(): List<NetworkChange> {
        return networkChanges.toList()
    }

    /**
     * Get recent network changes (last N seconds)
     */
    fun getRecentChanges(lastNSeconds: Int = 60): List<NetworkChange> {
        val cutoffTime = System.currentTimeMillis() - (lastNSeconds * 1000)
        return networkChanges.filter { it.timestamp >= cutoffTime }
    }

    /**
     * Check if network was recently lost
     */
    fun wasRecentlyLost(lastNSeconds: Int = 30): Boolean {
        val recentChanges = getRecentChanges(lastNSeconds)
        return recentChanges.any { it.event == "LOST" || !it.isConnected }
    }

    /**
     * Get current network state
     */
    fun getCurrentNetworkState(): NetworkChange? {
        return networkChanges.lastOrNull()
    }

    /**
     * Clear all changes
     */
    fun clear() {
        networkChanges.clear()
    }
}

/**
 * Network change event
 */
data class NetworkChange(
    val timestamp: Long,
    val event: String,  // AVAILABLE, LOST, CHANGED, INITIAL
    val networkType: String,  // WIFI, MOBILE, ETHERNET, VPN, NONE
    val isConnected: Boolean
)
