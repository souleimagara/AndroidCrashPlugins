package com.crashreporter.library

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Memory Warning Tracker
 * Tracks onLowMemory() and onTrimMemory() callbacks to detect memory pressure
 * leading up to crashes
 */
object MemoryWarningTracker : ComponentCallbacks2 {

    private const val MAX_WARNINGS = 20
    private val warnings = ConcurrentLinkedQueue<MemoryWarning>()
    private var isRegistered = false

    /**
     * Initialize memory warning tracking
     */
    fun initialize(application: Application) {
        if (isRegistered) {
            return
        }

        try {
            application.registerComponentCallbacks(this)
            isRegistered = true
            android.util.Log.i("MemoryWarningTracker", "✅ Memory warning tracking initialized")
        } catch (e: Exception) {
            android.util.Log.e("MemoryWarningTracker", "Failed to initialize", e)
        }
    }

    /**
     * Called when the system is running low on memory
     */
    override fun onLowMemory() {
        val warning = MemoryWarning(
            timestamp = System.currentTimeMillis(),
            level = "LOW_MEMORY",
            description = "System is running low on memory"
        )
        addWarning(warning)
        android.util.Log.w("MemoryWarningTracker", "⚠️ LOW_MEMORY warning")
    }

    /**
     * Called when the system needs to trim memory
     */
    override fun onTrimMemory(level: Int) {
        val (levelName, description) = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
                "TRIM_MEMORY_RUNNING_MODERATE" to "Device is running moderately low on memory"

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                "TRIM_MEMORY_RUNNING_LOW" to "Device is running low on memory"

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                "TRIM_MEMORY_RUNNING_CRITICAL" to "Device is running critically low on memory"

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                "TRIM_MEMORY_UI_HIDDEN" to "UI is hidden"

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                "TRIM_MEMORY_BACKGROUND" to "App is in background"

            ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
                "TRIM_MEMORY_MODERATE" to "System is moderately low on memory"

            ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                "TRIM_MEMORY_COMPLETE" to "System is extremely low on memory"

            else -> "TRIM_MEMORY_UNKNOWN_$level" to "Unknown trim memory level: $level"
        }

        val warning = MemoryWarning(
            timestamp = System.currentTimeMillis(),
            level = levelName,
            description = description
        )
        addWarning(warning)

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            android.util.Log.w("MemoryWarningTracker", "⚠️ Memory trim: $levelName")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Not needed for memory tracking
    }

    /**
     * Add warning to history (keep last 20)
     */
    private fun addWarning(warning: MemoryWarning) {
        warnings.add(warning)

        // Keep only last MAX_WARNINGS
        while (warnings.size > MAX_WARNINGS) {
            warnings.poll()
        }
    }

    /**
     * Get all memory warnings
     */
    fun getWarnings(): List<MemoryWarning> {
        return warnings.toList()
    }

    /**
     * Get recent memory warnings (last N seconds)
     */
    fun getRecentWarnings(lastNSeconds: Int = 60): List<MemoryWarning> {
        val cutoffTime = System.currentTimeMillis() - (lastNSeconds * 1000)
        return warnings.filter { it.timestamp >= cutoffTime }
    }

    /**
     * Check if there were recent critical memory warnings
     */
    fun hasRecentCriticalWarnings(lastNSeconds: Int = 30): Boolean {
        val recentWarnings = getRecentWarnings(lastNSeconds)
        return recentWarnings.any {
            it.level.contains("CRITICAL") || it.level == "LOW_MEMORY"
        }
    }

    /**
     * Clear all warnings
     */
    fun clear() {
        warnings.clear()
    }
}

/**
 * Memory warning event
 */
data class MemoryWarning(
    val timestamp: Long,
    val level: String,
    val description: String
)
