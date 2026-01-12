package com.crashreporter.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Listens for screen on/off events to automatically pause/resume ANR detection
 * This prevents false ANR reports when the device screen turns off
 */
class ScreenStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenStateReceiver"
        private const val RESUME_GRACE_PERIOD_MS = 10000L // 10 seconds
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var resumeRunnable: Runnable? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "📴 Screen turned OFF")
                Log.d(TAG, "   NOTE: ANR detection continues (validation engine will reject if screen-based false positive)")

                // DISABLED: We used to pause ANR detection here, but that prevented detection of real ANRs
                // that occur while the screen is on, then screen turns off during the blocked period.
                // Instead, ANRValidationEngine Factor 2 checks screen state and rejects appropriately.

                // Cancel any pending resume
                resumeRunnable?.let { mainHandler.removeCallbacks(it) }
                resumeRunnable = null
            }

            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "📱 Screen turned ON")
                Log.d(TAG, "   ANR detection continues normally")

                // DISABLED: We used to schedule a grace period resume, but ANR detection is no longer paused
                // So there's nothing to resume.
            }

            Intent.ACTION_USER_PRESENT -> {
                // User unlocked the device
                Log.d(TAG, "🔓 User unlocked device")
            }
        }
    }

    /**
     * Cleanup when receiver is unregistered
     */
    fun cleanup() {
        resumeRunnable?.let { mainHandler.removeCallbacks(it) }
        resumeRunnable = null
        Log.d(TAG, "🧹 Cleaned up pending ANR resume tasks")
    }
}
