package com.crashreporter.library

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Multi-factor ANR Validation Engine
 *
 * Eliminates false positives from:
 * - Device sleep (screen off)
 * - App backgrounding
 * - Power save mode
 * - Network loss
 *
 * Only reports ANR if app is truly unresponsive to user
 */
class ANRValidationEngine(
    private val context: Context,
    private val deviceInfoCollector: EnhancedDeviceInfoCollector,
    private val reachabilityTracker: ReachabilityTracker?
) {
    companion object {
        private const val TAG = "ANRValidationEngine"

        // ANR thresholds (milliseconds) - Optimized for network-dependent SDK
        // 15 seconds: Captures real freezes, allows for slow network operations on 3G/poor connections
        // 20 seconds (power save): Extra time for CPU throttling during battery saving
        // Rationale: SDK operations (rewards, init, balance check) legitimately take 5-15 seconds on slow networks
        // User force-closes at ~15-25s, so we need to detect before that window
        private const val STANDARD_ANR_THRESHOLD_MS = 15000L   // 15 seconds (normal mode)
        private const val POWER_SAVE_ANR_THRESHOLD_MS = 20000L  // 20 seconds (power save - extra time for throttling)
        private const val LOW_BATTERY_THRESHOLD = 0.05f       // 5% battery
        private const val NETWORK_LOSS_DURATION_MS = 30000L   // 30 seconds window
    }

    /**
     * Validate if ANR is real or false positive
     * Returns: ANRValidation with decision and confidence
     * @param blockedDurationMs How long the main thread was blocked
     * @param capturedProcessImportance Optional: process importance captured at ANR detection time (before Android backgrounds it)
     * @param capturedScreenState Optional: screen state captured at ANR detection time (true = screen was on)
     */
    fun isRealANR(blockedDurationMs: Long, capturedProcessImportance: String? = null, capturedScreenState: Boolean? = null): ANRValidation {
        try {
            // Get current device state, but use captured values if provided
            val processInfo = deviceInfoCollector.getProcessInfo()
            val processImportanceToCheck = capturedProcessImportance ?: processInfo.importance
            val deviceState = deviceInfoCollector.getDeviceState()
            val screenStateToCheck = capturedScreenState ?: deviceState.screenOn
            val isPowerSaveActive = deviceInfoCollector.isPowerSaveMode()
            val isBatteryLow = deviceState.batteryLevel < LOW_BATTERY_THRESHOLD
            val recentNetworkLoss = reachabilityTracker?.wasRecentlyLost(30) ?: false

            Log.d(TAG, "🔍 Validating ANR (${blockedDurationMs}ms blocked)")
            Log.d(TAG, "   Process Importance: ${processInfo.importance}")
            Log.d(TAG, "   Screen On: ${deviceState.screenOn}")
            Log.d(TAG, "   Power Save: $isPowerSaveActive")
            Log.d(TAG, "   Battery: ${(deviceState.batteryLevel * 100).toInt()}%")
            Log.d(TAG, "   Network Loss (recent): $recentNetworkLoss")

            // ==================== FACTOR 1: Process Importance (CRITICAL) ====================
            // Only report ANR if app was visible to user at detection time
            // Note: We use captured importance (at detection time), not current (Android backgrounds app during ANR)
            val isValidImportance = when (processImportanceToCheck) {
                "FOREGROUND" -> {
                    Log.d(TAG, "✅ Factor 1 (Importance): FOREGROUND - User could see app at detection")
                    true
                }
                "VISIBLE" -> {
                    Log.d(TAG, "✅ Factor 1 (Importance): VISIBLE - User could see app at detection")
                    true
                }
                else -> {
                    Log.d(TAG, "❌ Factor 1 (Importance): $processImportanceToCheck - App not visible to user at detection")
                    false
                }
            }

            if (!isValidImportance) {
                return ANRValidation(
                    isValid = false,
                    reason = "App in ${processInfo.importance} state - suspended by system, not user-visible ANR",
                    confidence = 99,
                    blockReason = "BACKGROUND_APP",
                    factors = ANRValidationFactors(
                        processImportance = processImportanceToCheck,
                        screenOn = screenStateToCheck,
                        networkLost = recentNetworkLoss,
                        powerSaveMode = isPowerSaveActive,
                        batteryLevel = deviceState.batteryLevel,
                        adjustedThreshold = STANDARD_ANR_THRESHOLD_MS
                    )
                )
            }

            // ==================== FACTOR 2: Screen State (IMPORTANT) ====================
            // Check if screen was on at ANR detection time
            // NOTE: We use captured screen state (at detection time), not current (might have changed)
            if (!screenStateToCheck) {
                Log.d(TAG, "❌ Factor 2 (Screen): Was OFF at detection - Device was sleeping during ANR")
                return ANRValidation(
                    isValid = false,
                    reason = "Device screen was off at ANR detection - app threads suspended by system during sleep",
                    confidence = 95,
                    blockReason = "SCREEN_OFF_AT_DETECTION",
                    factors = ANRValidationFactors(
                        processImportance = processImportanceToCheck,
                        screenOn = screenStateToCheck,
                        networkLost = recentNetworkLoss,
                        powerSaveMode = isPowerSaveActive,
                        batteryLevel = deviceState.batteryLevel,
                        adjustedThreshold = STANDARD_ANR_THRESHOLD_MS
                    )
                )
            }
            Log.d(TAG, "✅ Factor 2 (Screen): Was ON at detection - Device actively in use")

            // ==================== FACTOR 3: Power State (IMPORTANT) ====================
            // Adjust ANR threshold based on power state
            val adjustedThresholdMs = when {
                isPowerSaveActive || isBatteryLow -> {
                    Log.d(TAG, "⚠️  Factor 3 (Power): Power save active or battery low")
                    Log.d(TAG, "   Increasing ANR threshold from 15s to 20s")
                    POWER_SAVE_ANR_THRESHOLD_MS
                }
                else -> {
                    Log.d(TAG, "✅ Factor 3 (Power): Normal - Using standard 15s threshold")
                    STANDARD_ANR_THRESHOLD_MS
                }
            }

            // ==================== FACTOR 4: Network State (SUPPORTING) ====================
            // Network loss often correlates with device sleep
            if (recentNetworkLoss) {
                Log.w(TAG, "⚠️  Factor 4 (Network): Network lost recently - correlates with sleep")

                // If network lost and ANR duration is short, likely sleeping
                if (blockedDurationMs < POWER_SAVE_ANR_THRESHOLD_MS) {
                    Log.d(TAG, "❌ ANR duration ${blockedDurationMs}ms < 20s threshold during network loss")
                    return ANRValidation(
                        isValid = false,
                        reason = "Network recently lost + ANR duration only ${blockedDurationMs}ms (short for network loss scenario)",
                        confidence = 85,
                        blockReason = "NETWORK_LOSS_SHORT_DURATION",
                        factors = ANRValidationFactors(
                            processImportance = processInfo.importance,
                            screenOn = deviceState.screenOn,
                            networkLost = recentNetworkLoss,
                            powerSaveMode = isPowerSaveActive,
                            batteryLevel = deviceState.batteryLevel,
                            adjustedThreshold = POWER_SAVE_ANR_THRESHOLD_MS
                        )
                    )
                }
            } else {
                Log.d(TAG, "✅ Factor 4 (Network): Active - No recent network loss")
            }

            // ==================== FACTOR 5: ANR Duration vs Threshold (CRITICAL) ====================
            // Check if ANR duration exceeds the adjusted threshold
            if (blockedDurationMs < adjustedThresholdMs) {
                Log.d(TAG, "❌ Factor 5 (Duration): ${blockedDurationMs}ms < ${adjustedThresholdMs}ms threshold")
                return ANRValidation(
                    isValid = false,
                    reason = "ANR duration ${blockedDurationMs}ms below threshold ${adjustedThresholdMs}ms",
                    confidence = 80,
                    blockReason = "DURATION_TOO_SHORT",
                    factors = ANRValidationFactors(
                        processImportance = processImportanceToCheck,
                        screenOn = screenStateToCheck,
                        networkLost = recentNetworkLoss,
                        powerSaveMode = isPowerSaveActive,
                        batteryLevel = deviceState.batteryLevel,
                        adjustedThreshold = adjustedThresholdMs
                    )
                )
            }
            Log.d(TAG, "✅ Factor 5 (Duration): ${blockedDurationMs}ms >= ${adjustedThresholdMs}ms threshold")

            // ==================== ALL VALIDATIONS PASSED ✅ ====================
            Log.e(TAG, "✅ ANR VALIDATED: Real ANR detected (${blockedDurationMs}ms, foreground, screen on)")

            return ANRValidation(
                isValid = true,
                reason = "Real ANR: Foreground app, screen on, duration ${blockedDurationMs}ms >= ${adjustedThresholdMs}ms threshold",
                confidence = 99,
                blockReason = null,
                factors = ANRValidationFactors(
                    processImportance = processInfo.importance,
                    screenOn = deviceState.screenOn,
                    networkLost = recentNetworkLoss,
                    powerSaveMode = isPowerSaveActive,
                    batteryLevel = deviceState.batteryLevel,
                    adjustedThreshold = adjustedThresholdMs
                )
            )

        } catch (e: Exception) {
            // On error, default to allowing ANR (safer to report than miss real ANR)
            Log.e(TAG, "❌ Error during ANR validation: ${e.message}", e)
            return ANRValidation(
                isValid = true,
                reason = "Validation error (defaulting to report): ${e.message}",
                confidence = 50,
                blockReason = null,
                factors = ANRValidationFactors(
                    processImportance = "UNKNOWN",
                    screenOn = false,
                    networkLost = false,
                    powerSaveMode = false,
                    batteryLevel = 0f,
                    adjustedThreshold = STANDARD_ANR_THRESHOLD_MS
                )
            )
        }
    }
}

/**
 * ANR Validation result with detailed information
 */
data class ANRValidation(
    val isValid: Boolean,                           // true = report ANR, false = false positive
    val reason: String,                             // Why it was validated/rejected
    val confidence: Int,                            // 50-99% confidence in decision
    val blockReason: String? = null,                // Why blocked (if isValid = false)
    val factors: ANRValidationFactors = ANRValidationFactors() // Validation factors used
)

/**
 * ANR Validation factors breakdown
 */
data class ANRValidationFactors(
    val processImportance: String = "UNKNOWN",      // FOREGROUND, VISIBLE, BACKGROUND, etc.
    val screenOn: Boolean = false,                  // Is device screen on?
    val networkLost: Boolean = false,               // Was network recently lost?
    val powerSaveMode: Boolean = false,             // Is power save mode active?
    val batteryLevel: Float = 0f,                   // Current battery level (0.0-1.0)
    val adjustedThreshold: Long = 5000              // ANR threshold used (5s or 10s)
)
