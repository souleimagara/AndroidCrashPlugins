package com.crashreporter.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
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
    private var sessionId: String = java.util.UUID.randomUUID().toString()
    private var sessionStartTime: Long = 0L
    private lateinit var crashHandler: EnhancedCrashHandler
    private lateinit var crashStorage: CrashStorageProvider
    private lateinit var crashSender: EnhancedCrashSender
    private lateinit var deviceInfoCollector: EnhancedDeviceInfoCollector
    private lateinit var startupCrashDetector: StartupCrashDetector
    private lateinit var anrWatchdog: ANRWatchdog
    private lateinit var anrValidationEngine: ANRValidationEngine
    private var memoryWarningTracker: MemoryWarningTracker? = null
    private var reachabilityTracker: ReachabilityTracker? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // When true, native only STORES crashes — the host (Unity C#) pulls them via
    // getPendingCrashesAsJson(), signs each with the attestation key, and sends them itself
    // (events.zbd.lol + New Relic + webhook). Default false keeps the legacy native auto-send.
    @Volatile
    private var deferSendToHost = false

    /** Host opts in to driving the send (sign + deliver) itself. Native then only stores. */
    @JvmStatic
    fun setDeferSendToHost(enabled: Boolean) {
        deferSendToHost = enabled
        android.util.Log.i("EnhancedCrashReporter", "deferSendToHost = $enabled (host drives signed send)")
    }

    @JvmStatic
    fun isDeferSendToHost(): Boolean = deferSendToHost

    /**
     * Return all pending (stored, unsent) crashes as a JSON wrapper the host can parse:
     *   {"crashes":[{"crashId":"<id>","json":"<flattened crash payload>"}, ...]}
     * The "json" string is the EXACT payload the host signs (base64) and POSTs. The host
     * calls markCrashAsSent(crashId) after a successful delivery.
     */
    @JvmStatic
    fun getPendingCrashesAsJson(): String {
        return try {
            if (!::crashStorage.isInitialized || !::crashSender.isInitialized) return "{\"crashes\":[]}"
            val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().serializeNulls().create()
            val arr = com.google.gson.JsonArray()
            for (file in crashStorage.getPendingCrashFiles()) {
                try {
                    val crashId = file.nameWithoutExtension.removePrefix("crash_")
                    val crashData = kotlinx.coroutines.runBlocking { crashStorage.loadCrash(crashId) } ?: continue
                    val optimized = CrashGrouping.optimizePayload(crashData)
                    val entry = com.google.gson.JsonObject()
                    entry.addProperty("crashId", crashData.crashId)
                    entry.addProperty("json", crashSender.buildPayloadJson(optimized))
                    arr.add(entry)
                } catch (e: Exception) {
                    android.util.Log.e("EnhancedCrashReporter", "getPendingCrashesAsJson: error for ${file.name}", e)
                }
            }
            val wrapper = com.google.gson.JsonObject()
            wrapper.add("crashes", arr)
            gson.toJson(wrapper)
        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashReporter", "getPendingCrashesAsJson failed", e)
            "{\"crashes\":[]}"
        }
    }

    /** Host confirms a crash was delivered — remove it from pending storage. */
    @JvmStatic
    fun markCrashAsSent(crashId: String) {
        try {
            if (::crashStorage.isInitialized) {
                kotlinx.coroutines.runBlocking { crashStorage.markAsSent(crashId) }
                android.util.Log.i("EnhancedCrashReporter", "Host marked crash as sent: $crashId")
            }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashReporter", "markCrashAsSent failed for $crashId", e)
        }
    }
    private var appContext: Context? = null

    /**
     * Initialize the enhanced crash reporter
     */
    @JvmStatic
    fun initialize(context: Context, apiEndpoint: String, enableANRDetection: Boolean = true, apiKey: String = "", debugWebhookUrl: String = "") {
        if (isInitialized) {
            android.util.Log.w("EnhancedCrashReporter", "Already initialized, skipping...")
            return
        }

        try {
            appContext = context.applicationContext
            val appContext = context.applicationContext

            android.util.Log.i("EnhancedCrashReporter", "🚀 Initializing Enhanced Crash Reporter v2.0")

            // Initialize components
            crashStorage = FileCrashStorage(appContext)
            crashSender = EnhancedCrashSender()
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

            // Record session start time
            sessionStartTime = System.currentTimeMillis()

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

            // Process any pending native crashes from previous session (store for host to send)
            processNativeCrash()

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
                // Note: ANR pause/resume on focus-loss is handled by Unity's OnApplicationFocus
                // → pauseANRDetection() / resumeANRDetection() JNI calls, so no screen state
                // receiver is needed here.
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
                sdkConfidence = OperationTracker.getSDKConfidence(anrInfo.mainThreadStackTrace, OperationTracker.extractFaultingLibrary(anrInfo.mainThreadStackTrace)),
                faultingLibrary = OperationTracker.extractFaultingLibrary(anrInfo.mainThreadStackTrace),
                responsibleSDKComponent = OperationTracker.determineResponsibleComponent(anrInfo.mainThreadStackTrace),
                initFailurePoint = OperationTracker.getInitFailurePoint(),
                currentOperation = OperationTracker.getCurrentOperation() ?: "",
                operationContext = OperationTracker.getOperationContext(),
                sessionInfo = buildSessionInfo(inForeground = deviceInfoCollector.isInForeground()),
                memoryState = deviceInfoCollector.getMemoryState()
            )

            // Generate fingerprint
            val fingerprint = CrashGrouping.generateFingerprint(crashData)
            val updatedCrashData = crashData.copy(crashFingerprint = fingerprint)

            // Deduplicate: a recurring identical ANR (same fingerprint) shouldn't be stored/sent
            // over and over within the dedup window — collapse the repeats.
            if (CrashGrouping.isRecentDuplicate(fingerprint)) {
                android.util.Log.d("EnhancedCrashReporter", "⏭️ Duplicate ANR fingerprint ($fingerprint) — skipping store/send")
                return
            }

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

            // Stored to disk — the Unity host pulls, signs and sends it.
            android.util.Log.i("EnhancedCrashReporter", "⏸️ ANR stored — host will sign + send")

        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashReporter", "Error handling ANR", e)
        }
    }

    /**
     * Pending crashes are stored on disk; the Unity host pulls, signs and sends them
     * (getPendingCrashesAsJson → sign → POST). Native never sends, so this is a no-op kept
     * for the host-facing API surface.
     */
    @JvmStatic
    fun sendPendingCrashesNow() {
        android.util.Log.i("EnhancedCrashReporter", "⏸️ Pending crashes left for host to sign + send")
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

                    // Native data is now a pending storage entry the Unity host will pull, sign and
                    // send. Delete the raw native_crash.txt so it isn't re-parsed into a duplicate
                    // pending entry on the next launch.
                    android.util.Log.i("EnhancedCrashReporter", "⏸️ Native crash stored — host will sign + send: ${crashData.crashId}")
                    NativeCrashHandler.deleteNativeCrashFile()
                }
            } catch (e: Exception) {
                android.util.Log.e("EnhancedCrashReporter", "Error processing native crash: ${e.message}", e)
            }
        }
    }

    /**
     * Parse enhanced native crash file
     */
    /** Stable crashId from native crash content so a re-parse of the same file yields the same id. */
    private fun deterministicNativeCrashId(signal: String, faultAddress: String, stack: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("$signal|$faultAddress|$stack".toByteArray())
            "native-" + hash.take(16).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

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

        // Restore SDK context from disk. On a fresh process (after a native signal crash
        // killed the previous one), all OperationTracker singletons are empty. We wrote
        // a context snapshot to disk every 5 seconds via persistContextForNativeCrashRecovery(),
        // so we can recover the SDK state that was active when the crash happened.
        val persistedCtx = loadPersistedContext()
        val restoredCurrentOp = persistedCtx?.optString("currentOperation")?.takeIf { it.isNotEmpty() }
            ?: OperationTracker.getCurrentOperation()
        val restoredLastSuccess = persistedCtx?.optString("lastSuccessfulOperation")?.takeIf { it.isNotEmpty() }
            ?: OperationTracker.getLastSuccessfulOperation()
        val restoredLastFailed = persistedCtx?.optString("lastFailedOperation")?.takeIf { it.isNotEmpty() }
            ?: OperationTracker.getLastFailedOperation()
        val restoredFailureReason = persistedCtx?.optString("lastFailureReason")?.takeIf { it.isNotEmpty() }
            ?: OperationTracker.getLastFailureReason()
        val restoredSdkVersion = persistedCtx?.optString("sdkVersion")?.takeIf { it.isNotEmpty() }
            ?: OperationTracker.getSDKVersion()
        val restoredInitFailurePoint = persistedCtx?.optString("initFailurePoint")?.takeIf { it.isNotEmpty() }
            ?: OperationTracker.getInitFailurePoint()
        // Restore the environment saved before the crash. Without this, CustomDataManager
        // on fresh process defaults to "staging", making all native crashes show wrong env.
        val restoredEnvironment = persistedCtx?.optString("environment")?.takeIf { it.isNotEmpty() }
            ?: CustomDataManager.getEnvironment()

        if (persistedCtx != null) {
            android.util.Log.i("EnhancedCrashReporter", "✅ Restored SDK context from disk for native crash report")
        }

        val customDataWithOperations = CustomDataManager.getCustomData().toMutableMap()

        // Merge persisted custom data (init stages, user IDs, webview state etc.)
        persistedCtx?.optJSONObject("customData")?.let { savedCustomData ->
            savedCustomData.keys().forEach { key ->
                customDataWithOperations[key] = savedCustomData.getString(key)
            }
        }

        customDataWithOperations["currentOperation"] = restoredCurrentOp ?: "none"
        customDataWithOperations["lastSuccessfulOperation"] = restoredLastSuccess ?: "none"
        customDataWithOperations["lastFailedOperation"] = restoredLastFailed ?: "none"
        customDataWithOperations["lastOperationError"] = restoredFailureReason ?: "none"

        val crashData = CrashData(
            // Deterministic id from the crash content (not a random UUID): if the process died
            // between saving this crash and deleting native_crash.txt, the next launch re-parses the
            // same file → same id → the backend collapses the duplicate instead of logging two.
            crashId = deterministicNativeCrashId(signal, faultAddress, stackTrace.ifEmpty { content }),
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
            environment = restoredEnvironment,
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

            // SDK Context — use persisted values so native crash reports have real context
            sdkVersion = restoredSdkVersion,
            crashReporterPluginVersion = OperationTracker.getCrashReporterPluginVersion(),
            platform = OperationTracker.getPlatform(),
            isSDKRelated = OperationTracker.isSDKRelatedCrash(stackTrace),
            sdkConfidence = OperationTracker.getSDKConfidence(stackTrace, OperationTracker.extractFaultingLibrary(stackTrace)),
            faultingLibrary = OperationTracker.extractFaultingLibrary(stackTrace),
            responsibleSDKComponent = OperationTracker.determineResponsibleComponent(stackTrace),
            initFailurePoint = restoredInitFailurePoint,
            currentOperation = restoredCurrentOp ?: "",
            operationContext = OperationTracker.getOperationContext(),
            sessionInfo = buildSessionInfo(inForeground = false),
            memoryState = deviceInfoCollector.getMemoryState()
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
        customDataJson: String
    ) {
        // Parse the flat JSON string from the Unity C# bridge into a map.
        // Accepting a string instead of Map<String,String> avoids allocating a Java HashMap
        // and multiple JNI put() calls on the main thread inside the Unity exception handler.
        val customData: Map<String, String> = try {
            val json = org.json.JSONObject(customDataJson)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.optString(key, "") }
            map
        } catch (e: Exception) {
            android.util.Log.w("EnhancedCrashReporter", "Failed to parse customDataJson: ${e.message}")
            emptyMap()
        }

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

            // If C# passed a generic Unity LogType ("Exception", "Error", "Assert") instead of the
            // real class name, extract it from the start of errorMessage.
            // Format: "NullReferenceException: Some message" → "NullReferenceException"
            val resolvedExceptionType = if (exceptionType in listOf("Exception", "Error", "Assert", "Warning", "Log")) {
                val candidate = errorMessage.substringBefore(":").trim()
                if (candidate.isNotEmpty() && candidate.length < 80 && !candidate.contains(" "))
                    candidate
                else
                    exceptionType
            } else {
                exceptionType
            }

            // Collect all device data
            val crashData = CrashData(
                crashId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                exceptionType = resolvedExceptionType,
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
                sdkConfidence = OperationTracker.getSDKConfidence(stackTrace, OperationTracker.extractFaultingLibrary(stackTrace)),
                faultingLibrary = OperationTracker.extractFaultingLibrary(stackTrace),
                responsibleSDKComponent = OperationTracker.determineResponsibleComponent(stackTrace),
                initFailurePoint = OperationTracker.getInitFailurePoint(),
                currentOperation = OperationTracker.getCurrentOperation() ?: "",
                operationContext = OperationTracker.getOperationContext(),
                sessionInfo = buildSessionInfo(inForeground = deviceInfoCollector.isInForeground()),
                memoryState = deviceInfoCollector.getMemoryState()
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
            // Only actually-fatal crashes are CRITICAL. Managed exceptions come in with isFatal=false
            // and are HIGH (not CRITICAL) — matches iOS and keeps the fatal-crash SLO honest.
            // (Don't use determineSeverity here: its main-thread rule would wrongly mark every
            // managed main-thread exception CRITICAL.)
            val severity = if (isFatal) "CRITICAL" else "HIGH"

            val updatedCrashData = crashData.copy(
                crashFingerprint = fingerprint,
                issueTitle = issueTitle,
                severity = severity
            )

            android.util.Log.i("EnhancedCrashReporter", "📱 Managed exception prepared: type=$exceptionType, severity=$severity, fingerprint=$fingerprint")

            // Deduplicate: identical managed exceptions (same fingerprint) shouldn't be stored/sent
            // repeatedly within the dedup window — collapse the repeats.
            if (CrashGrouping.isRecentDuplicate(fingerprint)) {
                android.util.Log.d("EnhancedCrashReporter", "⏭️ Duplicate managed exception fingerprint ($fingerprint) — skipping store/send")
                return
            }

            // Persist and send asynchronously
            scope.launch {
                try {
                    // 📱 DIAGNOSTIC: Log save start
                    android.util.Log.d("EnhancedCrashReporter", "📱 Saving managed exception to disk...")

                    // Save to disk
                    crashStorage.saveCrash(updatedCrashData)

                    android.util.Log.i("EnhancedCrashReporter", "📱 Managed exception saved to disk")
                    android.util.Log.i("EnhancedCrashReporter", "⏸️ Managed exception stored — host will sign + send")
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

    // Signature (content minus timestamp) of the last context written to disk, so the frequent
    // periodic pushes don't rewrite identical data.
    @Volatile private var lastPersistedContextSignature: String? = null

    /**
     * Persist current SDK context (OperationTracker + CustomDataManager) to disk so that
     * native signal crash recovery can read it on the next app launch.
     *
     * WHY: When a native signal crash (SIGSEGV, SIGABRT etc.) kills the process, the next
     * launch starts a fresh JVM with empty singletons. parseNativeCrash() would read
     * OperationTracker.getCurrentOperation() etc. and get "none" for all fields.
     * By periodically persisting context to disk (called every 5s from Unity C# bridge),
     * we ensure the last known SDK state survives the crash and appears in the report.
     *
     * Called from Unity via JNI: ZBDAndroidCrashBridge.SendSDKContextToNative()
     *
     * Returns true only when it actually wrote to disk (false when skipped/unchanged or on error),
     * so the host can log accurately instead of every tick.
     */
    @JvmStatic
    fun persistContextForNativeCrashRecovery(): Boolean {
        try {
            val ctx = appContext ?: return false
            val contextFile = File(ctx.filesDir, "zbd_crash_context.json")

            val json = JSONObject().apply {
                put("currentOperation", OperationTracker.getCurrentOperation() ?: "")
                put("lastSuccessfulOperation", OperationTracker.getLastSuccessfulOperation() ?: "")
                put("lastFailedOperation", OperationTracker.getLastFailedOperation() ?: "")
                put("lastFailureReason", OperationTracker.getLastFailureReason() ?: "")
                put("sdkVersion", OperationTracker.getSDKVersion())
                put("initFailurePoint", OperationTracker.getInitFailurePoint())
                // Persist environment — CustomDataManager defaults to "staging" on fresh process,
                // so we must save the real value here so native crash reports show the correct env.
                put("environment", CustomDataManager.getEnvironment())

                // Custom tags from SDK (includes init stages, user IDs, webview state etc.
                // pushed by ZBDAndroidCrashBridge.SendSDKContextToNative)
                val customDataJson = JSONObject()
                CustomDataManager.getCustomData().forEach { (k, v) -> customDataJson.put(k, v) }
                put("customData", customDataJson)
            }

            // Skip the write when nothing changed — this runs on a frequent timer from the Unity
            // bridge, and rewriting identical context is pointless disk I/O in the host game. The
            // timestamp is added only when we actually write, so it doesn't defeat this check.
            val signature = json.toString()
            if (signature == lastPersistedContextSignature) return false
            lastPersistedContextSignature = signature

            json.put("timestamp", System.currentTimeMillis())
            contextFile.writeText(json.toString())
            android.util.Log.d("EnhancedCrashReporter", "✅ SDK context persisted for native crash recovery")
            return true
        } catch (e: Exception) {
            android.util.Log.w("EnhancedCrashReporter", "Failed to persist context: ${e.message}")
            return false
        }
    }

    /**
     * Load previously persisted SDK context from disk.
     * Used by parseNativeCrash() to restore context that was active before the crash.
     */
    private fun loadPersistedContext(): JSONObject? {
        return try {
            val ctx = appContext ?: return null
            val contextFile = File(ctx.filesDir, "zbd_crash_context.json")
            if (!contextFile.exists()) return null
            JSONObject(contextFile.readText())
        } catch (e: Exception) {
            android.util.Log.w("EnhancedCrashReporter", "Failed to load persisted context: ${e.message}")
            null
        }
    }

    /**
     * Build a SessionInfo snapshot for the current session
     */
    private fun buildSessionInfo(inForeground: Boolean = true): SessionInfo {
        val now = System.currentTimeMillis()
        val start = if (sessionStartTime > 0L) sessionStartTime else now
        return SessionInfo(
            sessionId = sessionId,
            sessionStartTime = start,
            sessionDurationMs = now - start,
            isInForeground = inForeground,
            eventsBeforeCrash = BreadcrumbManager.getBreadcrumbs().size,
            appWasInBackground = !inForeground
        )
    }

    /**
     * Stop the crash reporter
     */
    @JvmStatic
    fun shutdown() {
        if (::anrWatchdog.isInitialized) {
            anrWatchdog.stopWatchdog()
        }

        reachabilityTracker?.stopTracking()
        memoryWarningTracker?.clear()
        appContext = null
        isInitialized = false
        android.util.Log.i("EnhancedCrashReporter", "Crash reporter shut down")
    }
}
