package com.crashreporter.library

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR (Application Not Responding) Watchdog
 * Detects when main thread is blocked for 15+ seconds (normal) or 20+ seconds (power save)
 *
 * ANRs are a major crash category (20-30% of all crashes)
 * This watches the main thread and reports if it becomes unresponsive
 *
 * Multi-factor validation eliminates false positives from:
 * - Device sleep (screen off)
 * - App backgrounding
 * - Power save mode
 * - Network delays (SDK operations can legitimately take 5-15 seconds)
 */
class ANRWatchdog(
    private val onANRDetected: (anrInfo: ANRInfo) -> Unit,
    private val validationEngine: ANRValidationEngine? = null,  // Optional validation engine
    private val deviceInfoCollector: EnhancedDeviceInfoCollector? = null,  // Capture device state at detection
    private val timeoutMs: Long = 15000  // 15 seconds (normal) - can be overridden if needed
) : Thread("ANRWatchdog") {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastPingTime = AtomicLong(System.currentTimeMillis())
    private val lastANRReportTime = AtomicLong(0)
    @Volatile private var running = true
    @Volatile private var paused = false
    @Volatile private var lastProcessImportance = "UNKNOWN"  // Capture process state when detected
    @Volatile private var lastScreenState = true  // Capture screen state when detected (true = on)

    companion object {
        private const val ANR_COOLDOWN_MS = 30000L // 30 seconds cooldown between ANR reports
    }

    init {
        isDaemon = true
    }

    override fun run() {
        android.util.Log.i("ANRWatchdog", "ANR Watchdog started (timeout: ${timeoutMs}ms)")

        while (running) {
            try {
                // Post a runnable to main thread to update ping time
                mainHandler.post {
                    lastPingTime.set(System.currentTimeMillis())
                }

                // Determine appropriate sleep duration based on power save mode
                val sleepDuration = calculateSleepDuration()

                // Wait for timeout period
                sleep(sleepDuration)

                // Check if main thread responded
                if (!paused) {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastPing = currentTime - lastPingTime.get()
                    val anrThresholdMs = calculateANRThreshold()

                    if (timeSinceLastPing > anrThresholdMs) {
                        // Check cooldown to prevent multiple reports
                        val timeSinceLastReport = currentTime - lastANRReportTime.get()

                        if (timeSinceLastReport > ANR_COOLDOWN_MS) {
                            // ANR detected and cooldown passed - CAPTURE device state at this moment
                            android.util.Log.e("ANRWatchdog", "⚠️ ANR DETECTED! Main thread blocked for ${timeSinceLastPing}ms")

                            // Capture device state at detection time (before things change)
                            if (deviceInfoCollector != null) {
                                val processInfo = deviceInfoCollector.getProcessInfo()
                                val deviceState = deviceInfoCollector.getDeviceState()
                                lastProcessImportance = processInfo.importance
                                lastScreenState = deviceState.screenOn
                                android.util.Log.d("ANRWatchdog", "📊 Captured at ANR detection - Importance: $lastProcessImportance, Screen: ${if (lastScreenState) "ON" else "OFF"}")
                            }

                            // Validate ANR before reporting (multi-factor validation)
                            if (validationEngine != null) {
                                val validation = validationEngine.isRealANR(timeSinceLastPing, lastProcessImportance, lastScreenState)
                                val anrInfo = collectANRInfo(timeSinceLastPing, validation)

                                if (validation.isValid) {
                                    android.util.Log.e("ANRWatchdog", "✅ ANR VALIDATED: ${validation.reason}")
                                    android.util.Log.i("ANRWatchdog", "   Confidence: ${validation.confidence}%")
                                    onANRDetected(anrInfo)
                                    lastANRReportTime.set(currentTime)
                                } else {
                                    android.util.Log.d("ANRWatchdog", "❌ ANR REJECTED (False Positive)")
                                    android.util.Log.d("ANRWatchdog", "   Reason: ${validation.reason}")
                                    android.util.Log.d("ANRWatchdog", "   Block Reason: ${validation.blockReason}")
                                    android.util.Log.d("ANRWatchdog", "   Confidence: ${validation.confidence}%")
                                    // Don't report this ANR - it's a false positive
                                }
                            } else {
                                // No validation engine - report ANR directly (legacy behavior)
                                android.util.Log.w("ANRWatchdog", "⚠️  No validation engine - reporting ANR without validation")
                                val anrInfo = collectANRInfo(timeSinceLastPing, null)
                                onANRDetected(anrInfo)
                                lastANRReportTime.set(currentTime)
                            }

                            // Wait a bit before checking again
                            sleep(timeoutMs)
                        } else {
                            // Still in cooldown - skip this ANR
                            android.util.Log.d("ANRWatchdog", "ANR detected but in cooldown period (${ANR_COOLDOWN_MS - timeSinceLastReport}ms remaining)")
                            sleep(1000) // Check again in 1 second
                        }
                    }
                }
            } catch (e: InterruptedException) {
                android.util.Log.d("ANRWatchdog", "ANR Watchdog interrupted")
                break
            } catch (e: Exception) {
                android.util.Log.e("ANRWatchdog", "Error in ANR watchdog", e)
            }
        }

        android.util.Log.i("ANRWatchdog", "ANR Watchdog stopped")
    }

    /**
     * Collect ANR information including all thread stack traces
     */
    private fun collectANRInfo(blockedDurationMs: Long, validation: ANRValidation? = null): ANRInfo {
        val allThreads = mutableListOf<ThreadInfo>()

        // Get stack traces of ALL threads
        Thread.getAllStackTraces().forEach { (thread, stackTrace) ->
            allThreads.add(
                ThreadInfo(
                    id = thread.id,
                    name = thread.name,
                    state = thread.state.name,
                    priority = thread.priority,
                    isDaemon = thread.isDaemon,
                    stackTrace = stackTrace.joinToString("\n") {
                        "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                    }
                )
            )
        }

        // Find main thread
        val mainThread = allThreads.find { it.name == "main" || it.id == Looper.getMainLooper().thread.id }

        return ANRInfo(
            blockedDurationMs = blockedDurationMs,
            timestamp = System.currentTimeMillis(),
            mainThreadStackTrace = mainThread?.stackTrace ?: "Main thread not found",
            allThreads = allThreads,
            validation = validation  // Include validation result
        )
    }

    /**
     * Pause ANR detection (e.g., during known long operations)
     */
    fun pause() {
        paused = true
        android.util.Log.d("ANRWatchdog", "ANR detection paused")
    }

    /**
     * Resume ANR detection
     */
    fun resumeANRDetection() {
        paused = false
        lastPingTime.set(System.currentTimeMillis())
        android.util.Log.d("ANRWatchdog", "ANR detection resumed")
    }

    /**
     * Stop the watchdog
     */
    fun stopWatchdog() {
        running = false
        interrupt()
    }

    /**
     * Calculate appropriate sleep duration based on device power state
     * Sleep duration must match the ANR threshold for consistency
     * 15 seconds (normal): Captures real freezes, allows for slow network operations
     * 20 seconds (power save): Extra time for CPU throttling during battery saving
     */
    private fun calculateSleepDuration(): Long {
        return try {
            if (deviceInfoCollector != null) {
                val isPowerSaveActive = deviceInfoCollector.isPowerSaveMode()
                val deviceState = deviceInfoCollector.getDeviceState()
                val isBatteryLow = deviceState.batteryLevel < 0.05f

                when {
                    isPowerSaveActive || isBatteryLow -> {
                        // Power save mode: sleep for 20 seconds before checking
                        android.util.Log.d("ANRWatchdog", "💤 Power save active - sleeping 20000ms before ANR check")
                        20000L
                    }
                    else -> {
                        // Normal mode: sleep for 15 seconds before checking
                        android.util.Log.d("ANRWatchdog", "💤 Normal mode - sleeping 15000ms before ANR check")
                        15000L
                    }
                }
            } else {
                timeoutMs
            }
        } catch (e: Exception) {
            android.util.Log.e("ANRWatchdog", "Error calculating sleep duration: ${e.message}", e)
            timeoutMs
        }
    }

    /**
     * Calculate ANR threshold based on device power state
     * This must match the sleep duration for consistency
     * 15 seconds (normal): Captures real freezes, allows for slow network operations
     * 20 seconds (power save): Extra time for CPU throttling during battery saving
     */
    private fun calculateANRThreshold(): Long {
        return try {
            if (deviceInfoCollector != null) {
                val isPowerSaveActive = deviceInfoCollector.isPowerSaveMode()
                val deviceState = deviceInfoCollector.getDeviceState()
                val isBatteryLow = deviceState.batteryLevel < 0.05f

                when {
                    isPowerSaveActive || isBatteryLow -> {
                        // Power save mode: require 20+ seconds of blocked main thread
                        20000L
                    }
                    else -> {
                        // Normal mode: require 15+ seconds of blocked main thread
                        // Captures real freezes while allowing for slow network operations
                        15000L
                    }
                }
            } else {
                15000L
            }
        } catch (e: Exception) {
            android.util.Log.e("ANRWatchdog", "Error calculating ANR threshold: ${e.message}", e)
            15000L
        }
    }
}

/**
 * ANR information with validation data
 */
data class ANRInfo(
    val blockedDurationMs: Long,
    val timestamp: Long,
    val mainThreadStackTrace: String,
    val allThreads: List<ThreadInfo>,
    val validation: ANRValidation? = null  // Validation result (if validation engine used)
)
