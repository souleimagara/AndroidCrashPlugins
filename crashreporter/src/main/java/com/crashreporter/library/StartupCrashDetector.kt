package com.crashreporter.library

import android.content.Context
import android.content.SharedPreferences

/**
 * Startup Crash Detector
 * Detects crashes that occur within the first few seconds of app launch
 * Also detects crash loops (multiple crashes in short time)
 */
class StartupCrashDetector(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("crash_reporter_startup", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_APP_STARTED = "app_started"
        private const val KEY_APP_STARTED_TIME = "app_started_time"
        private const val KEY_STARTUP_CRASH_COUNT = "startup_crash_count"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"

        private const val STARTUP_WINDOW_MS = 5000L  // 5 seconds
        private const val CRASH_LOOP_WINDOW_MS = 60000L  // 1 minute
        private const val CRASH_LOOP_THRESHOLD = 3  // 3 crashes in 1 minute = crash loop
    }

    /**
     * Mark that app has started
     * Call this in Application.onCreate() or Activity.onCreate()
     */
    fun markAppStarted() {
        prefs.edit()
            .putBoolean(KEY_APP_STARTED, true)
            .putLong(KEY_APP_STARTED_TIME, System.currentTimeMillis())
            .apply()

        android.util.Log.d("StartupCrashDetector", "App marked as started")
    }

    /**
     * Mark that app has successfully initialized
     * Call this after critical initialization is complete
     */
    fun markAppInitialized() {
        prefs.edit()
            .putBoolean(KEY_APP_STARTED, false)
            .apply()

        android.util.Log.d("StartupCrashDetector", "App marked as initialized successfully")
    }

    /**
     * Check if previous launch crashed during startup
     * Call this at app launch to detect startup crashes
     */
    fun didCrashOnStartup(): Boolean {
        val wasStarted = prefs.getBoolean(KEY_APP_STARTED, false)
        val startTime = prefs.getLong(KEY_APP_STARTED_TIME, 0)

        if (wasStarted) {
            // App was started but never marked as initialized
            // This means it crashed during startup
            val crashedAt = startTime
            val now = System.currentTimeMillis()

            // Check if crash was recent (within startup window)
            val timeSinceCrash = now - crashedAt
            if (timeSinceCrash < STARTUP_WINDOW_MS * 10) {  // Allow some time tolerance
                android.util.Log.w("StartupCrashDetector", "⚠️ Detected startup crash from previous launch")

                // Increment crash count
                incrementStartupCrashCount()

                return true
            }
        }

        return false
    }

    /**
     * Check if app is in a crash loop
     */
    fun isInCrashLoop(): Boolean {
        val crashCount = prefs.getInt(KEY_STARTUP_CRASH_COUNT, 0)
        val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0)
        val now = System.currentTimeMillis()

        // Reset count if last crash was more than 1 minute ago
        if (now - lastCrashTime > CRASH_LOOP_WINDOW_MS) {
            resetCrashCount()
            return false
        }

        // Check if crash count exceeds threshold
        if (crashCount >= CRASH_LOOP_THRESHOLD) {
            android.util.Log.e("StartupCrashDetector", "🔴 CRASH LOOP DETECTED! $crashCount crashes in ${CRASH_LOOP_WINDOW_MS}ms")
            return true
        }

        return false
    }

    /**
     * Record a crash
     */
    fun recordCrash() {
        val startTime = prefs.getLong(KEY_APP_STARTED_TIME, 0)
        val now = System.currentTimeMillis()
        val isStartupCrash = (now - startTime) < STARTUP_WINDOW_MS

        prefs.edit()
            .putLong(KEY_LAST_CRASH_TIME, now)
            .apply()

        if (isStartupCrash) {
            incrementStartupCrashCount()
        }

        android.util.Log.d("StartupCrashDetector", "Crash recorded (startup: $isStartupCrash)")
    }

    /**
     * Get startup crash info
     */
    fun getStartupCrashInfo(): StartupCrashInfo {
        return StartupCrashInfo(
            isStartupCrash = didCrashOnStartup(),
            isInCrashLoop = isInCrashLoop(),
            startupCrashCount = prefs.getInt(KEY_STARTUP_CRASH_COUNT, 0),
            lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0),
            appStartTime = prefs.getLong(KEY_APP_STARTED_TIME, 0)
        )
    }

    private fun incrementStartupCrashCount() {
        val count = prefs.getInt(KEY_STARTUP_CRASH_COUNT, 0)
        prefs.edit()
            .putInt(KEY_STARTUP_CRASH_COUNT, count + 1)
            .apply()
    }

    private fun resetCrashCount() {
        prefs.edit()
            .putInt(KEY_STARTUP_CRASH_COUNT, 0)
            .apply()
    }

    /**
     * Reset all startup crash tracking
     * Call this after successfully handling a crash loop
     */
    fun reset() {
        prefs.edit()
            .clear()
            .apply()
        android.util.Log.d("StartupCrashDetector", "Startup crash detector reset")
    }
}

data class StartupCrashInfo(
    val isStartupCrash: Boolean,
    val isInCrashLoop: Boolean,
    val startupCrashCount: Int,
    val lastCrashTime: Long,
    val appStartTime: Long
)
