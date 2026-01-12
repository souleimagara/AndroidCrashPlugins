package com.crashreporter.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ENHANCED Crash Reporter Library
 *
 * NEW FEATURES:
 * - ✅ Prevents duplicate sends
 * - ✅ ANR detection
 * - ✅ Startup crash detection
 * - ✅ Crash grouping/fingerprinting
 * - ✅ Exponential backoff retry
 * - ✅ Register dumps for native crashes
 * - ✅ Memory dumps around fault address
 * - ✅ All missing device fields
 * - ✅ Abstraction layers for testing
 */
object EnhancedCrashReporter {

    // CONFIGURABLE ANR THRESHOLD (for SLO optimization)
    // Production: 60000ms (60 seconds) - only catastrophic ANRs
    // Testing: 15000ms (15 seconds) - catch realistic ANRs
    private var ANR_THRESHOLD_MS = 60000L

    private var isInitialized = false
    private lateinit var crashHandler: EnhancedCrashHandler
    private lateinit var crashStorage: CrashStorageProvider
    private lateinit var crashSender: EnhancedCrashSender
    private lateinit var deviceInfoCollector: EnhancedDeviceInfoCollector
    private lateinit var startupCrashDetector: StartupCrashDetector
    private lateinit var anrWatchdog: ANRWatchdog
    private lateinit var anrValidationEngine: ANRValidationEngine  // ADD THIS
    private lateinit var screenStateReceiver: ScreenStateReceiver
    private var memoryWarningTracker: MemoryWarningTracker? = null
    private var reachabilityTracker: ReachabilityTracker? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

    /**
     * Initialize the enhanced crash reporter
     */
    @JvmStatic
    fun initialize(context: Context, apiEndpoint: String, enableANRDetection: Boolean = true) {
        if (isInitialized) {
            android.util.Log.w("EnhancedCrashReporter", "Already initialized, skipping...")
            return
        }

        try {
            val appContext = context.applicationContext

            android.util.Log.i("EnhancedCrashReporter", "🚀 Initializing Enhanced Crash Reporter v2.0")

            // Initialize components
            crashStorage = FileCrashStorage(appContext)
            crashSender = EnhancedCrashSender(apiEndpoint, crashStorage)
            deviceInfoCollector = EnhancedDeviceInfoCollector(appContext)
            startupCrashDetector = StartupCrashDetector(appContext)

            // Initialize persistent fingerprint storage (CRITICAL: prevents duplicate crashes)
            try {
                CrashGrouping.initialize(appContext)
                android.util.Log.i("EnhancedCrashReporter", "✅ Persistent fingerprint storage initialized")
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Failed to initialize fingerprint storage: ${e.message}")
            }

            // Check for startup crashes from previous session
            val startupInfo = startupCrashDetector.getStartupCrashInfo()
            if (startupInfo.isStartupCrash) {
                android.util.Log.w("EnhancedCrashReporter", "⚠️ Previous session crashed during startup")
            }
            if (startupInfo.isInCrashLoop) {
                android.util.Log.e("EnhancedCrashReporter", "🔴 CRASH LOOP DETECTED! ${startupInfo.startupCrashCount} crashes")
                // You could disable features or show a safe mode UI here
            }

            // Mark app as started
            startupCrashDetector.markAppStarted()

            // Initialize memory and network trackers
            try {
                memoryWarningTracker = MemoryWarningTracker
                if (appContext is android.app.Application) {
                    memoryWarningTracker?.initialize(appContext)
                    android.util.Log.i("EnhancedCrashReporter", "✅ Memory warning tracker initialized")
                }
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Failed to initialize memory tracker: ${e.message}")
            }

            try {
                reachabilityTracker = ReachabilityTracker(appContext)
                reachabilityTracker?.startTracking()
                android.util.Log.i("EnhancedCrashReporter", "✅ Reachability tracker started")
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Failed to initialize reachability tracker: ${e.message}")
            }

            crashHandler = EnhancedCrashHandler(
                context = appContext,
                crashStorage = crashStorage,
                crashSender = crashSender,
                deviceInfoCollector = deviceInfoCollector,
                startupCrashDetector = startupCrashDetector,
                memoryWarningTracker = memoryWarningTracker,
                reachabilityTracker = reachabilityTracker
            )

            // Set up the uncaught exception handler
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
            android.util.Log.i("EnhancedCrashReporter", "✅ Exception handler installed")

            // Initialize native crash handler
            try {
                NativeCrashHandler.initialize(appContext)
                android.util.Log.i("EnhancedCrashReporter", "✅ Native crash handler initialized")
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Failed to initialize native crash handler: ${e.message}")
            }

            // Process any pending native crashes from previous session
            processNativeCrash()

            // Send any pending crashes from previous sessions
            sendPendingCrashes()

            // Initialize ANR watchdog (optional)
            if (enableANRDetection) {
                // Create validation engine first
                anrValidationEngine = ANRValidationEngine(
                    context = appContext,
                    deviceInfoCollector = deviceInfoCollector,
                    reachabilityTracker = reachabilityTracker
                )
                android.util.Log.i("EnhancedCrashReporter", "✅ ANR validation engine created")

                anrWatchdog = ANRWatchdog(
                    onANRDetected = { anrInfo ->
                        handleANR(anrInfo)
                    },
                    validationEngine = anrValidationEngine,  // Pass validation engine
                    deviceInfoCollector = deviceInfoCollector,  // Pass device info collector to capture process state at detection
                    timeoutMs = ANR_THRESHOLD_MS  // Use configurable threshold (60 seconds for production)
                )
                anrWatchdog.start()
                android.util.Log.i("EnhancedCrashReporter", "✅ ANR watchdog started with multi-factor validation")

                // Register screen state receiver to auto-pause ANR detection
                try {
                    this.appContext = appContext
                    screenStateReceiver = ScreenStateReceiver()
                    val filter = android.content.IntentFilter().apply {
                        addAction(android.content.Intent.ACTION_SCREEN_ON)
                        addAction(android.content.Intent.ACTION_SCREEN_OFF)
                        addAction(android.content.Intent.ACTION_USER_PRESENT)
                    }
                    appContext.registerReceiver(screenStateReceiver, filter)
                    android.util.Log.i("EnhancedCrashReporter", "✅ Screen state receiver registered (auto ANR pause/resume)")
                } catch (e: Exception) {
                    android.util.Log.w("EnhancedCrashReporter", "Failed to register screen state receiver: ${e.message}")
                }
            }

            // Cleanup old crashes
            scope.launch {
                if (crashStorage is FileCrashStorage) {
                    (crashStorage as FileCrashStorage).cleanupOldSentCrashes()
                }
            }

            isInitialized = true
            android.util.Log.i("EnhancedCrashReporter", "🎉 Enhanced Crash Reporter initialized successfully")

        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashReporter", "Failed to initialize: ${e.message}", e)
        }
    }

    /**
     * Mark that app has successfully initialized (for startup crash detection)
     */
    @JvmStatic
    fun markAppInitialized() {
        if (::startupCrashDetector.isInitialized) {
            startupCrashDetector.markAppInitialized()
        }
    }

    /**
     * Set ANR detection threshold (in milliseconds)
     *
     * RECOMMENDED VALUES:
     * - Production: 60000 (60 seconds) - only catastrophic ANRs for SLO
     * - Testing: 15000 (15 seconds) - catch realistic ANRs
     * - Aggressive: 5000 (5 seconds) - catch any responsiveness issue
     *
     * @param thresholdMs Duration in milliseconds
     */
    @JvmStatic
    fun setANRThreshold(thresholdMs: Long) {
        if (thresholdMs < 1000) {
            android.util.Log.w("EnhancedCrashReporter", "⚠️ ANR threshold below 1 second ($thresholdMs ms) may cause excessive false positives")
        }
        ANR_THRESHOLD_MS = thresholdMs
        android.util.Log.i("EnhancedCrashReporter", "✅ ANR detection threshold updated to ${thresholdMs}ms")
    }

    /**
     * Handle ANR detection with validation data
     * CRITICAL: Persist to disk IMMEDIATELY (before async operations)
     * This ensures ANR data survives even if user force-closes the app
     */
    private fun handleANR(anrInfo: ANRInfo) {
        try {
            // ════════════════════════════════════════════════════════════
            // PHASE 1: BUILD crash data (can be done on watchdog thread)
            // ════════════════════════════════════════════════════════════
            android.util.Log.e("EnhancedCrashReporter", "🔥 ANR DETECTED! Blocked for ${anrInfo.blockedDurationMs}ms")

            // Add validation data to custom data if available
            val customDataWithValidation = CustomDataManager.getCustomData().toMutableMap()

            // ════════════════════════════════════════════════════════════
            // ADD OPERATION TRACKING DATA FOR SLO MONITORING
            // ════════════════════════════════════════════════════════════
            try {
                val currentOp = OperationTracker.getCurrentOperation()
                val lastSuccessOp = OperationTracker.getLastSuccessfulOperation()
                val lastFailedOp = OperationTracker.getLastFailedOperation()
                val lastFailureReason = OperationTracker.getLastFailureReason()

                customDataWithValidation["currentOperation"] = currentOp ?: "none"
                customDataWithValidation["lastSuccessfulOperation"] = lastSuccessOp ?: "none"
                customDataWithValidation["lastFailedOperation"] = lastFailedOp ?: "none"
                customDataWithValidation["lastOperationError"] = lastFailureReason ?: "none"

                android.util.Log.d("EnhancedCrashReporter", "📊 Operation tracking added to ANR report:")
                android.util.Log.d("EnhancedCrashReporter", "   currentOperation: ${currentOp ?: "none"}")
                android.util.Log.d("EnhancedCrashReporter", "   lastFailedOperation: ${lastFailedOp ?: "none"}")
                android.util.Log.d("EnhancedCrashReporter", "   lastOperationError: ${lastFailureReason ?: "none"}")
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Failed to get operation tracking data: ${e.message}")
            }

            if (anrInfo.validation != null) {
                val validation = anrInfo.validation
                customDataWithValidation["anr_validation_isValid"] = validation.isValid.toString()
                customDataWithValidation["anr_validation_reason"] = validation.reason
                customDataWithValidation["anr_validation_confidence"] = validation.confidence.toString()
                customDataWithValidation["anr_validation_blockReason"] = validation.blockReason ?: "NONE"

                // Add validation factors
                customDataWithValidation["anr_factor_processImportance"] = validation.factors.processImportance
                customDataWithValidation["anr_factor_screenOn"] = validation.factors.screenOn.toString()
                customDataWithValidation["anr_factor_networkLost"] = validation.factors.networkLost.toString()
                customDataWithValidation["anr_factor_powerSaveMode"] = validation.factors.powerSaveMode.toString()
                customDataWithValidation["anr_factor_batteryLevel"] = (validation.factors.batteryLevel * 100).toInt().toString()
                customDataWithValidation["anr_factor_adjustedThreshold"] = validation.factors.adjustedThreshold.toString()

                android.util.Log.i("EnhancedCrashReporter", "📊 ANR Validation Data:")
                android.util.Log.i("EnhancedCrashReporter", "   isValid: ${validation.isValid}")
                android.util.Log.i("EnhancedCrashReporter", "   confidence: ${validation.confidence}%")
                android.util.Log.i("EnhancedCrashReporter", "   blockReason: ${validation.blockReason}")
                android.util.Log.i("EnhancedCrashReporter", "   reason: ${validation.reason}")
            }

            // Create ANR crash report
            val crashData = CrashData(
                crashId = UUID.randomUUID().toString(),
                timestamp = anrInfo.timestamp,
                exceptionType = "ANR",
                exceptionMessage = "Application Not Responding (${anrInfo.blockedDurationMs}ms)",
                stackTrace = anrInfo.mainThreadStackTrace,
                threadName = "main",
                deviceInfo = deviceInfoCollector.getDeviceInfo(),
                appInfo = deviceInfoCollector.getAppInfo(),
                deviceState = deviceInfoCollector.getDeviceState(),
                networkInfo = deviceInfoCollector.getNetworkInfo(),
                memoryInfo = deviceInfoCollector.getMemoryInfo(),
                cpuInfo = deviceInfoCollector.getCpuInfo(),
                processInfo = deviceInfoCollector.getProcessInfo(),
                allThreads = anrInfo.allThreads,
                breadcrumbs = BreadcrumbManager.getBreadcrumbs(),
                customData = customDataWithValidation,  // Include validation data
                environment = CustomDataManager.getEnvironment(),
                crashFingerprint = "",
                issueTitle = "ANR - Application Not Responding",
                severity = "CRITICAL",
                isANR = true,
                anrDurationMs = anrInfo.blockedDurationMs,
                isStartupCrash = false,
                isInCrashLoop = false,
                crashLoopCount = 0,
                powerSaveMode = deviceInfoCollector.isPowerSaveMode(),
                developerMode = deviceInfoCollector.isDeveloperMode(),
                isDebugBuild = deviceInfoCollector.isDebugBuild(),
                bootTime = deviceInfoCollector.getBootTime(),
                deviceUptime = deviceInfoCollector.getDeviceUptime(),
                timezone = deviceInfoCollector.getTimezone(),
                memoryWarnings = memoryWarningTracker?.getWarnings() ?: emptyList(),
                memoryPressure = deviceInfoCollector.getMemoryPressure(),
                networkChanges = reachabilityTracker?.getNetworkChanges() ?: emptyList(),
                wasNetworkRecentlyLost = reachabilityTracker?.wasRecentlyLost(30) ?: false,
                isVPNActive = deviceInfoCollector.isVPNActive(),
                isProxyActive = deviceInfoCollector.isProxyActive(),
                sdCardInfo = deviceInfoCollector.getExternalSDCardInfo(),
                diskPerformance = deviceInfoCollector.getDiskPerformance(),

                // SDK Context (Common SLO fields)
                sdkVersion = OperationTracker.getSDKVersion(),
                crashReporterPluginVersion = OperationTracker.getCrashReporterPluginVersion(),
                platform = OperationTracker.getPlatform(),
                isSDKRelated = OperationTracker.isSDKRelatedCrash(anrInfo.mainThreadStackTrace),
                responsibleSDKComponent = OperationTracker.determineResponsibleComponent(anrInfo.mainThreadStackTrace),
                initFailurePoint = OperationTracker.getInitFailurePoint(),
                currentOperation = OperationTracker.getCurrentOperation() ?: "",
                operationContext = OperationTracker.getOperationContext()
            )

            // Generate fingerprint
            val fingerprint = CrashGrouping.generateFingerprint(crashData)
            val updatedCrashData = crashData.copy(crashFingerprint = fingerprint)

            // ════════════════════════════════════════════════════════════
            // PHASE 2: PERSIST immediately (synchronous, on watchdog thread)
            // This MUST happen before ANY async operations or coroutine launches
            // If user force-closes now, this data is SAFE on disk
            // ════════════════════════════════════════════════════════════
            try {
                // Use runBlocking to call the suspend function synchronously from watchdog thread
                kotlinx.coroutines.runBlocking {
                    crashStorage.saveCrash(updatedCrashData)
                }
                android.util.Log.i("EnhancedCrashReporter", "✅ ANR PERSISTED TO DISK - safe from force-close")
                android.util.Log.i("EnhancedCrashReporter", "   Crash ID: ${updatedCrashData.crashId}")
                android.util.Log.i("EnhancedCrashReporter", "   Duration: ${anrInfo.blockedDurationMs}ms")
            } catch (e: Exception) {
                android.util.Log.e("EnhancedCrashReporter", "🚨 CRITICAL: Failed to persist ANR crash to disk!", e)
                // Even if persistence fails, we log the error but continue
                // The crash data is at least in memory for this session
            }

            // ════════════════════════════════════════════════════════════
            // PHASE 3: SEND asynchronously (defer to background coroutine)
            // This is less critical - can happen later or after app restart
            // ════════════════════════════════════════════════════════════
            scope.launch {
                try {
                    crashSender.sendCrash(updatedCrashData)
                    android.util.Log.i("EnhancedCrashReporter", "✅ ANR report sent to webhook")
                } catch (e: Exception) {
                    android.util.Log.e("EnhancedCrashReporter", "Error sending ANR crash (will retry on next session)", e)
                    // Crash is already persisted, so this failure is not critical
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashReporter", "Error handling ANR", e)
        }
    }

    /**
     * Send all pending crashes
     */
    private fun sendPendingCrashes() {
        scope.launch {
            try {
                crashSender.sendAllPendingCrashes()
            } catch (e: Exception) {
                android.util.Log.e("EnhancedCrashReporter", "Error sending pending crashes: ${e.message}", e)
            }
        }
    }

    /**
     * Manually trigger sending pending crashes
     */
    @JvmStatic
    fun sendPendingCrashesNow() {
        sendPendingCrashes()
    }

    /**
     * Check if initialized
     */
    @JvmStatic
    fun isInitialized(): Boolean = isInitialized

    /**
     * Get pending crash count
     */
    @JvmStatic
    fun getPendingCrashCount(): Int {
        return if (::crashStorage.isInitialized) {
            crashStorage.getPendingCrashCount()
        } else {
            0
        }
    }

    /**
     * Process native crash from previous session
     */
    private fun processNativeCrash() {
        scope.launch {
            try {
                val nativeCrashFile = NativeCrashHandler.getPendingNativeCrash()
                if (nativeCrashFile != null) {
                    android.util.Log.i("EnhancedCrashReporter", "🔍 Found native crash from previous session")

                    val nativeCrashContent = nativeCrashFile.readText()
                    val crashData = parseNativeCrash(nativeCrashContent)

                    crashStorage.saveCrash(crashData)

                    val success = crashSender.sendCrash(crashData)
                    if (success) {
                        android.util.Log.i("EnhancedCrashReporter", "✅ Native crash sent successfully")
                        NativeCrashHandler.deleteNativeCrashFile()
                    } else {
                        android.util.Log.w("EnhancedCrashReporter", "⚠️ Failed to send native crash, will retry later")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("EnhancedCrashReporter", "Error processing native crash: ${e.message}", e)
            }
        }
    }

    /**
     * Parse enhanced native crash file
     */
    private fun parseNativeCrash(content: String): CrashData {
        val lines = content.split("\n")
        var signal = "UNKNOWN"
        var description = "Native crash"
        var faultAddress = "unknown"
        var threadName = "unknown"
        var stackTrace = ""
        val registers = mutableMapOf<String, String>()
        var memoryDump = ""

        var inRegisters = false
        var inStackTrace = false
        var inMemoryDump = false

        for (line in lines) {
            when {
                line.startsWith("Signal:") -> signal = line.substringAfter("Signal:").trim()
                line.startsWith("Description:") -> description = line.substringAfter("Description:").trim()
                line.startsWith("Fault Address:") -> faultAddress = line.substringAfter("Fault Address:").trim()
                line.startsWith("Thread:") -> threadName = line.substringAfter("Thread:").trim()
                line.startsWith("REGISTERS:") -> { inRegisters = true; inStackTrace = false; inMemoryDump = false }
                line.startsWith("STACK TRACE:") -> { inRegisters = false; inStackTrace = true; inMemoryDump = false }
                line.startsWith("MEMORY DUMP:") -> { inRegisters = false; inStackTrace = false; inMemoryDump = true }
                inRegisters && line.contains(":") -> {
                    val parts = line.trim().split(":")
                    if (parts.size == 2) {
                        registers[parts[0].trim()] = parts[1].trim()
                    }
                }
                inStackTrace && (line.startsWith("#") || line.trim().startsWith("at ")) -> stackTrace += line + "\n"
                inMemoryDump -> memoryDump += line + "\n"
            }
        }

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

            android.util.Log.d("EnhancedCrashReporter", "📊 Operation tracking added to native crash report")
        } catch (e: Exception) {
            android.util.Log.w("EnhancedCrashReporter", "Failed to get operation tracking for native crash: ${e.message}")
        }

        val crashData = CrashData(
            crashId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            exceptionType = signal,
            exceptionMessage = "$description at $faultAddress",
            stackTrace = stackTrace.ifEmpty { content },
            threadName = threadName,
            deviceInfo = deviceInfoCollector.getDeviceInfo(),
            appInfo = deviceInfoCollector.getAppInfo(),
            deviceState = deviceInfoCollector.getDeviceState(),
            networkInfo = deviceInfoCollector.getNetworkInfo(),
            memoryInfo = deviceInfoCollector.getMemoryInfo(),
            cpuInfo = deviceInfoCollector.getCpuInfo(),
            processInfo = deviceInfoCollector.getProcessInfo(),
            allThreads = emptyList(),
            breadcrumbs = BreadcrumbManager.getBreadcrumbs(),
            customData = customDataWithOperations,
            environment = CustomDataManager.getEnvironment(),
            crashFingerprint = "",
            issueTitle = "",
            severity = "",
            isANR = false,
            anrDurationMs = 0,
            isStartupCrash = false,
            isInCrashLoop = false,
            crashLoopCount = 0,
            powerSaveMode = deviceInfoCollector.isPowerSaveMode(),
            developerMode = deviceInfoCollector.isDeveloperMode(),
            isDebugBuild = deviceInfoCollector.isDebugBuild(),
            bootTime = deviceInfoCollector.getBootTime(),
            deviceUptime = deviceInfoCollector.getDeviceUptime(),
            timezone = deviceInfoCollector.getTimezone(),
            isNativeCrash = true,
            nativeSignal = signal,
            nativeFaultAddress = faultAddress,
            nativeRegisters = registers,
            memoryDump = memoryDump,
            memoryWarnings = memoryWarningTracker?.getWarnings() ?: emptyList(),
            memoryPressure = deviceInfoCollector.getMemoryPressure(),
            networkChanges = reachabilityTracker?.getNetworkChanges() ?: emptyList(),
            wasNetworkRecentlyLost = reachabilityTracker?.wasRecentlyLost(30) ?: false,
            isVPNActive = deviceInfoCollector.isVPNActive(),
            isProxyActive = deviceInfoCollector.isProxyActive(),
            sdCardInfo = deviceInfoCollector.getExternalSDCardInfo(),
            diskPerformance = deviceInfoCollector.getDiskPerformance(),

            // SDK Context (Common SLO fields)
            sdkVersion = OperationTracker.getSDKVersion(),
            crashReporterPluginVersion = OperationTracker.getCrashReporterPluginVersion(),
            platform = OperationTracker.getPlatform(),
            isSDKRelated = OperationTracker.isSDKRelatedCrash(stackTrace),
            responsibleSDKComponent = OperationTracker.determineResponsibleComponent(stackTrace),
            initFailurePoint = OperationTracker.getInitFailurePoint(),
            currentOperation = OperationTracker.getCurrentOperation() ?: "",
            operationContext = OperationTracker.getOperationContext()
        )

        // Generate fingerprint
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
     * Trigger a native crash for testing
     */
    @JvmStatic
    fun triggerNativeCrash(type: Int) {
        android.util.Log.w("EnhancedCrashReporter", "⚠️ Triggering native crash type: $type")
        NativeCrashHandler.triggerTestCrash(type)
    }

    /**
     * Pause ANR detection (e.g., during known long operations)
     */
    @JvmStatic
    fun pauseANRDetection() {
        if (::anrWatchdog.isInitialized) {
            anrWatchdog.pause()
        }
    }

    /**
     * Resume ANR detection
     */
    @JvmStatic
    fun resumeANRDetection() {
        if (::anrWatchdog.isInitialized) {
            anrWatchdog.resumeANRDetection()
        }
    }

    /**
     * Handle managed exception from C# with full device data collection
     * Called from Unity C# bridge to ensure consistent device data capture
     *
     * @param exceptionType Exception type (e.g., "NullReferenceException")
     * @param errorMessage Exception message
     * @param stackTrace Full stack trace
     * @param isFatal Whether this is a fatal crash
     * @param customData Additional context from C#
     */
    @JvmStatic
    fun handleManagedException(
        exceptionType: String,
        errorMessage: String,
        stackTrace: String,
        isFatal: Boolean,
        customData: Map<String, String>
    ) {
        try {
            // 📱 DIAGNOSTIC: Log method entry
            android.util.Log.i("EnhancedCrashReporter", "📱 handleManagedException ENTRY: type=$exceptionType, fatal=$isFatal, customDataSize=${customData.size}")

            if (!isInitialized) {
                android.util.Log.w("EnhancedCrashReporter", "❌ Crash reporter not initialized, skipping managed exception")
                return
            }

            val customDataWithTracking = customData.toMutableMap()

            // Add operation tracking data
            try {
                val currentOp = OperationTracker.getCurrentOperation()
                val lastSuccessOp = OperationTracker.getLastSuccessfulOperation()
                val lastFailedOp = OperationTracker.getLastFailedOperation()
                val lastFailureReason = OperationTracker.getLastFailureReason()

                customDataWithTracking["currentOperation"] = currentOp ?: "none"
                customDataWithTracking["lastSuccessfulOperation"] = lastSuccessOp ?: "none"
                customDataWithTracking["lastFailedOperation"] = lastFailedOp ?: "none"
                customDataWithTracking["lastOperationError"] = lastFailureReason ?: "none"

                android.util.Log.d("EnhancedCrashReporter", "📱 Operation tracking: current=$currentOp, lastSuccess=$lastSuccessOp, lastFailed=$lastFailedOp")
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Failed to get operation tracking: ${e.message}")
            }

            // 📱 DIAGNOSTIC: Log device data collection start
            android.util.Log.d("EnhancedCrashReporter", "📱 Starting device data collection for managed exception...")

            // Collect all device data
            val crashData = CrashData(
                crashId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                exceptionType = exceptionType,
                exceptionMessage = errorMessage,
                stackTrace = stackTrace,
                threadName = "main",
                deviceInfo = deviceInfoCollector.getDeviceInfo(),
                appInfo = deviceInfoCollector.getAppInfo(),
                deviceState = deviceInfoCollector.getDeviceState(),
                networkInfo = deviceInfoCollector.getNetworkInfo(),
                memoryInfo = deviceInfoCollector.getMemoryInfo(),
                cpuInfo = deviceInfoCollector.getCpuInfo(),
                processInfo = deviceInfoCollector.getProcessInfo(),
                allThreads = getAllManagedThreadInfo(),
                breadcrumbs = BreadcrumbManager.getBreadcrumbs(),
                customData = customDataWithTracking,
                environment = CustomDataManager.getEnvironment(),

                // New SLO fields
                crashFingerprint = "",
                issueTitle = "",
                severity = "",
                isANR = false,
                anrDurationMs = 0,
                isStartupCrash = false,
                isInCrashLoop = false,
                crashLoopCount = 0,
                powerSaveMode = deviceInfoCollector.isPowerSaveMode(),
                developerMode = deviceInfoCollector.isDeveloperMode(),
                isDebugBuild = deviceInfoCollector.isDebugBuild(),
                bootTime = deviceInfoCollector.getBootTime(),
                deviceUptime = deviceInfoCollector.getDeviceUptime(),
                timezone = deviceInfoCollector.getTimezone(),
                memoryWarnings = memoryWarningTracker?.getWarnings() ?: emptyList(),
                memoryPressure = deviceInfoCollector.getMemoryPressure(),
                networkChanges = reachabilityTracker?.getNetworkChanges() ?: emptyList(),
                wasNetworkRecentlyLost = reachabilityTracker?.wasRecentlyLost(30) ?: false,
                isVPNActive = deviceInfoCollector.isVPNActive(),
                isProxyActive = deviceInfoCollector.isProxyActive(),
                sdCardInfo = deviceInfoCollector.getExternalSDCardInfo(),
                diskPerformance = deviceInfoCollector.getDiskPerformance(),

                // SDK Context
                sdkVersion = OperationTracker.getSDKVersion(),
                crashReporterPluginVersion = OperationTracker.getCrashReporterPluginVersion(),
                platform = OperationTracker.getPlatform(),
                isSDKRelated = OperationTracker.isSDKRelatedCrash(stackTrace),
                responsibleSDKComponent = OperationTracker.determineResponsibleComponent(stackTrace),
                initFailurePoint = OperationTracker.getInitFailurePoint(),
                currentOperation = OperationTracker.getCurrentOperation() ?: "",
                operationContext = OperationTracker.getOperationContext()
            )

            // 📱 DIAGNOSTIC: Log device data collected
            android.util.Log.d("EnhancedCrashReporter", "📱 Device data collected: " +
                    "deviceInfo=${crashData.deviceInfo != null}, " +
                    "memoryInfo=${crashData.memoryInfo != null}, " +
                    "cpuInfo=${crashData.cpuInfo != null}, " +
                    "networkInfo=${crashData.networkInfo != null}, " +
                    "processInfo=${crashData.processInfo != null}")

            // Generate fingerprint and metadata
            val fingerprint = CrashGrouping.generateFingerprint(crashData)
            val issueTitle = CrashGrouping.generateIssueTitle(crashData)
            val severity = if (isFatal) "CRITICAL" else CrashGrouping.determineSeverity(crashData).name

            val updatedCrashData = crashData.copy(
                crashFingerprint = fingerprint,
                issueTitle = issueTitle,
                severity = severity
            )

            android.util.Log.i("EnhancedCrashReporter", "📱 Managed exception prepared: type=$exceptionType, severity=$severity, fingerprint=$fingerprint")

            // Persist and send asynchronously
            scope.launch {
                try {
                    // 📱 DIAGNOSTIC: Log save start
                    android.util.Log.d("EnhancedCrashReporter", "📱 Saving managed exception to disk...")

                    // Save to disk
                    crashStorage.saveCrash(updatedCrashData)

                    android.util.Log.i("EnhancedCrashReporter", "📱 Managed exception saved to disk")

                    // Send to webhook
                    android.util.Log.d("EnhancedCrashReporter", "📱 Sending managed exception to webhook...")
                    crashSender.sendCrash(updatedCrashData)
                    android.util.Log.i("EnhancedCrashReporter", "✅ Managed exception sent to webhook successfully")
                } catch (e: Exception) {
                    android.util.Log.e("EnhancedCrashReporter", "❌ Error handling managed exception (will retry later): ${e.message}", e)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashReporter", "❌ ERROR in handleManagedException: ${e.message}", e)
        }
    }

    /**
     * Get thread info for managed exception context
     */
    private fun getAllManagedThreadInfo(): List<ThreadInfo> {
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
            android.util.Log.w("EnhancedCrashReporter", "Error getting thread info for managed exception", e)
        }
        return threads
    }

    /**
     * Stop the crash reporter
     */
    @JvmStatic
    fun shutdown() {
        if (::anrWatchdog.isInitialized) {
            anrWatchdog.stopWatchdog()
        }

        // Unregister screen state receiver
        if (::screenStateReceiver.isInitialized) {
            try {
                appContext?.unregisterReceiver(screenStateReceiver)
                screenStateReceiver.cleanup()
                android.util.Log.i("EnhancedCrashReporter", "✅ Screen state receiver unregistered")
            } catch (e: Exception) {
                android.util.Log.w("EnhancedCrashReporter", "Error unregistering screen state receiver: ${e.message}")
            }
        }

        reachabilityTracker?.stopTracking()
        memoryWarningTracker?.clear()
        appContext = null
        isInitialized = false
        android.util.Log.i("EnhancedCrashReporter", "Crash reporter shut down")
    }
}
