package com.crashreporter.library

import android.content.Context
import android.os.Build
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Enhanced Crash Handler with duplicate prevention and better error handling
 * FIXED: Prevents duplicate sends by immediately marking crashes
 */
class EnhancedCrashHandler(
    private val context: Context,
    private val crashStorage: CrashStorageProvider,
    private val crashSender: EnhancedCrashSender,
    private val deviceInfoCollector: EnhancedDeviceInfoCollector,
    private val startupCrashDetector: StartupCrashDetector,
    private val memoryWarningTracker: MemoryWarningTracker? = null,
    private val reachabilityTracker: ReachabilityTracker? = null
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            android.util.Log.e("EnhancedCrashHandler", "🔥 Uncaught exception detected", throwable)

            // Record crash for startup detection
            startupCrashDetector.recordCrash()

            // CHECK FOR CRASH LOOP: If 5+ crashes in first 60 seconds, don't report
            val startupInfo = startupCrashDetector.getStartupCrashInfo()
            val timeSinceBoot = System.currentTimeMillis() - startupInfo.appStartTime
            if (startupInfo.startupCrashCount >= 5 && timeSinceBoot < 60000) {
                android.util.Log.e("EnhancedCrashHandler", "🔁 CRASH LOOP DETECTED: ${startupInfo.startupCrashCount} crashes in ${timeSinceBoot}ms")
                android.util.Log.e("EnhancedCrashHandler", "⛔ Disabling crash reporting to prevent infinite loop")
                // Call original handler and exit (don't report this crash)
                defaultHandler?.uncaughtException(thread, throwable)
                return
            }

            // Collect crash data
            val crashData = collectCrashData(thread, throwable)

            // CRITICAL: Save crash FIRST (blocking)
            runBlocking {
                val saved = crashStorage.saveCrash(crashData)
                if (!saved) {
                    // Fallback: Write to logcat if save fails
                    android.util.Log.e("EnhancedCrashHandler", "CRASH SAVE FAILED: ${crashData.crashId}")
                    android.util.Log.e("EnhancedCrashHandler", "Stack: ${crashData.stackTrace}")
                }
            }

            // Try to send immediately (non-blocking, best effort)
            runBlocking {
                try {
                    crashSender.sendCrash(crashData)
                } catch (e: Exception) {
                    android.util.Log.d("EnhancedCrashHandler", "Failed to send crash immediately: ${e.message}")
                    // OK if fails, will be sent on next app launch
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashHandler", "Error in crash handler", e)
            // Still try to log original crash
            android.util.Log.e("EnhancedCrashHandler", "Original crash: ${throwable.message}", throwable)
        } finally {
            // ALWAYS call original handler to ensure app terminates properly
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun collectCrashData(thread: Thread, throwable: Throwable): CrashData {
        val timestamp = System.currentTimeMillis()
        val crashId = UUID.randomUUID().toString()

        // Add operation tracking data to custom data for SLO monitoring
        val customDataWithOperations = CustomDataManager.getCustomData().toMutableMap()
        try {
            val currentOp = OperationTracker.getCurrentOperation()
            val lastSuccessOp = OperationTracker.getLastSuccessfulOperation()
            val lastFailedOp = OperationTracker.getLastFailedOperation()
            val lastFailureReason = OperationTracker.getLastFailureReason()

            customDataWithOperations["currentOperation"] = currentOp ?: "none"
            customDataWithOperations["lastSuccessfulOperation"] = lastSuccessOp ?: "none"
            customDataWithOperations["lastFailedOperation"] = lastFailedOp ?: "none"
            customDataWithOperations["lastOperationError"] = lastFailureReason ?: "none"

            android.util.Log.d("EnhancedCrashHandler", "📊 Operation tracking added to exception report")
        } catch (e: Exception) {
            android.util.Log.w("EnhancedCrashHandler", "Failed to get operation tracking for exception: ${e.message}")
        }

        // Collect all data
        val crashData = CrashData(
            crashId = crashId,
            timestamp = timestamp,
            exceptionType = throwable.javaClass.name,
            exceptionMessage = throwable.message ?: "No message",
            stackTrace = getDetailedStackTrace(throwable),
            threadName = thread.name,
            deviceInfo = deviceInfoCollector.getDeviceInfo(),
            appInfo = deviceInfoCollector.getAppInfo(),
            deviceState = deviceInfoCollector.getDeviceState(),
            networkInfo = deviceInfoCollector.getNetworkInfo(),
            memoryInfo = deviceInfoCollector.getMemoryInfo(),
            cpuInfo = deviceInfoCollector.getCpuInfo(),
            processInfo = deviceInfoCollector.getProcessInfo(),
            allThreads = getAllThreadStackTraces(),
            breadcrumbs = BreadcrumbManager.getBreadcrumbs(),
            customData = customDataWithOperations,
            environment = CustomDataManager.getEnvironment(),

            // NEW FIELDS
            crashFingerprint = "",  // Will be set below
            issueTitle = "",        // Will be set below
            severity = "",          // Will be set below
            isANR = false,
            anrDurationMs = 0,
            isStartupCrash = (timestamp - startupCrashDetector.getStartupCrashInfo().appStartTime) < 5000,
            isInCrashLoop = startupCrashDetector.isInCrashLoop(),
            crashLoopCount = startupCrashDetector.getStartupCrashInfo().startupCrashCount,
            powerSaveMode = deviceInfoCollector.isPowerSaveMode(),
            developerMode = deviceInfoCollector.isDeveloperMode(),
            isDebugBuild = deviceInfoCollector.isDebugBuild(),
            bootTime = deviceInfoCollector.getBootTime(),
            deviceUptime = deviceInfoCollector.getDeviceUptime(),
            timezone = deviceInfoCollector.getTimezone(),

            // NEW FIELDS - Memory Warnings & Network Tracking
            memoryWarnings = memoryWarningTracker?.getWarnings() ?: emptyList(),
            memoryPressure = deviceInfoCollector.getMemoryPressure(),
            networkChanges = reachabilityTracker?.getNetworkChanges() ?: emptyList(),
            wasNetworkRecentlyLost = reachabilityTracker?.wasRecentlyLost(30) ?: false,

            // NEW FIELDS - Additional Device Info
            isVPNActive = deviceInfoCollector.isVPNActive(),
            isProxyActive = deviceInfoCollector.isProxyActive(),
            sdCardInfo = deviceInfoCollector.getExternalSDCardInfo(),
            diskPerformance = deviceInfoCollector.getDiskPerformance(),
            recentLogcat = captureRecentLogcat(),

            // NEW FIELDS - SDK Context (Common SLO fields)
            sdkVersion = OperationTracker.getSDKVersion(),
            crashReporterPluginVersion = OperationTracker.getCrashReporterPluginVersion(),
            platform = OperationTracker.getPlatform(),
            isSDKRelated = OperationTracker.isSDKRelatedCrash(getDetailedStackTrace(throwable)),
            responsibleSDKComponent = OperationTracker.determineResponsibleComponent(getDetailedStackTrace(throwable)),
            initFailurePoint = OperationTracker.getInitFailurePoint(),
            currentOperation = OperationTracker.getCurrentOperation() ?: "",
            operationContext = OperationTracker.getOperationContext()
        )

        // Generate fingerprint and metadata
        val fingerprint = CrashGrouping.generateFingerprint(crashData)
        val issueTitle = CrashGrouping.generateIssueTitle(crashData)
        val severity = CrashGrouping.determineSeverity(crashData).name

        return crashData.copy(
            crashFingerprint = fingerprint,
            issueTitle = issueTitle,
            severity = severity
        )
    }

    /**
     * Capture last 50 lines of logcat (max 5KB)
     */
    private fun captureRecentLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -v threadtime")
            val logLines = process.inputStream.bufferedReader().readLines()
            val recentLines = logLines.takeLast(50)  // Last 50 lines
            val logcat = recentLines.joinToString("\n")

            // Truncate to 5KB max
            if (logcat.length > 5000) {
                logcat.take(5000 - 15) + " [truncated]"
            } else {
                logcat
            }
        } catch (e: Exception) {
            android.util.Log.w("EnhancedCrashHandler", "Failed to capture logcat: ${e.message}")
            ""  // Return empty string if capture fails
        }
    }

    private fun getDetailedStackTrace(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)

        // Add cause chain
        var cause = throwable.cause
        while (cause != null) {
            printWriter.println("Caused by:")
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }

        return stringWriter.toString()
    }

    private fun getAllThreadStackTraces(): List<ThreadInfo> {
        val threads = mutableListOf<ThreadInfo>()

        try {
            Thread.getAllStackTraces().forEach { (thread, stackTrace) ->
                threads.add(
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
        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashHandler", "Error getting thread info", e)
        }

        return threads
    }
}
