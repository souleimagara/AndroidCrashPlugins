package com.crashreporter.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main entry point for the Crash Reporter Library
 *
 * Usage from Unity (C#):
 * AndroidJavaClass crashReporter = new AndroidJavaClass("com.crashreporter.library.CrashReporter");
 * crashReporter.CallStatic("initialize", context, "https://your-api.com");
 */
object CrashReporter {

    private var isInitialized = false
    private lateinit var crashHandler: CrashHandler
    private lateinit var crashStorage: CrashStorage
    private lateinit var crashSender: CrashSender
    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize the crash reporter
     *
     * @param context Android application context
     * @param apiEndpoint The base URL of your crash reporting API (e.g., "https://api.example.com")
     */
    @JvmStatic
    fun initialize(context: Context, apiEndpoint: String) {
        if (isInitialized) {
            android.util.Log.w("CrashReporter", "Already initialized, skipping...")
            return
        }

        try {
            val appContext = context.applicationContext

            // Initialize components
            crashStorage = CrashStorage(appContext)
            crashSender = CrashSender(apiEndpoint, crashStorage)
            deviceInfoCollector = DeviceInfoCollector(appContext)
            crashHandler = CrashHandler(
                context = appContext,
                crashStorage = crashStorage,
                crashSender = crashSender,
                deviceInfoCollector = deviceInfoCollector
            )

            // Set up the uncaught exception handler
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)

            // Send any pending crashes from previous sessions
            sendPendingCrashes()

            isInitialized = true
            android.util.Log.i("CrashReporter", "Crash Reporter initialized successfully")

        } catch (e: Exception) {
            android.util.Log.e("CrashReporter", "Failed to initialize: ${e.message}", e)
        }
    }

    /**
     * Send all pending crashes that were saved from previous sessions
     */
    private fun sendPendingCrashes() {
        scope.launch {
            try {
                crashSender.sendAllPendingCrashes()
            } catch (e: Exception) {
                android.util.Log.e("CrashReporter", "Error sending pending crashes: ${e.message}", e)
            }
        }
    }

    /**
     * Manually trigger sending pending crashes (optional, for testing)
     */
    @JvmStatic
    fun sendPendingCrashesNow() {
        sendPendingCrashes()
    }

    /**
     * Check if the crash reporter is initialized
     */
    @JvmStatic
    fun isInitialized(): Boolean = isInitialized

    /**
     * Get the number of pending crashes waiting to be sent
     */
    @JvmStatic
    fun getPendingCrashCount(): Int {
        return if (::crashStorage.isInitialized) {
            crashStorage.getPendingCrashFiles().size
        } else {
            0
        }
    }
}
