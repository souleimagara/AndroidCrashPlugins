package com.crashreporter.library

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow

/**
 * Enhanced Crash Sender with Cost Control
 *
 * Features:
 * - Deduplication via fingerprint tracking
 * - Sampling: 100% fatal, 15% non-fatal
 * - Payload optimization and compression
 * - Batching for non-fatal crashes
 * - Exponential backoff retry
 * - Intelligent null/empty field exclusion (~20% size reduction)
 */
class EnhancedCrashSender(
    private val apiEndpoint: String,
    private val crashStorage: CrashStorageProvider,
    private val networkProvider: NetworkProvider = OkHttpNetworkProvider(),
    private val apiKey: String = "",
    private val debugWebhookUrl: String = ""   // Optional secondary endpoint (e.g. webhook.site) for payload inspection
) {

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 5000L
        private const val MAX_RETRY_DELAY_MS = 60000L
        private const val BATCH_SIZE = 10
        private const val BATCH_TIMEOUT_MS = 60000L
        private const val MAX_QUEUE_SIZE = 100  // Prevent unbounded queue growth
    }

    // Batching state
    private val batchQueue = mutableListOf<CrashData>()
    private val batchMutex = Mutex()
    private var lastBatchTime = System.currentTimeMillis()

    /**
     * Process crash with cost control - main entry point
     */
    suspend fun processCrash(crashData: CrashData): Boolean = withContext(Dispatchers.IO) {
        // Step 1: Check if should send (deduplication + sampling)
        when (val decision = CrashGrouping.shouldSendCrash(crashData)) {
            is SendDecision.Skip -> {
                android.util.Log.d("EnhancedCrashSender", "⏭️ Skipped: ${decision.reason}")
                return@withContext true  // Not an error
            }
            is SendDecision.IncrementOnly -> {
                android.util.Log.d("EnhancedCrashSender", "🔢 Duplicate #${decision.count}: ${decision.fingerprint}")
                sendCounterUpdate(decision.fingerprint, decision.count)
                // Mark as sent so it is removed from pending storage and doesn't loop forever
                crashStorage.markAsSent(crashData.crashId)
                return@withContext true
            }
            is SendDecision.AddToBatch -> {
                val optimized = CrashGrouping.optimizePayload(crashData)
                addToBatch(optimized)
                return@withContext true
            }
            is SendDecision.SendImmediately -> {
                val optimized = CrashGrouping.optimizePayload(crashData)
                return@withContext sendCrash(optimized)
            }
        }
    }

    /**
     * Send a single crash report with retry logic
     * Automatically optimizes payload to ensure it meets size limits
     */
    suspend fun sendCrash(crashData: CrashData, attemptNumber: Int = 0): Boolean = withContext(Dispatchers.IO) {
        try {
            // CRITICAL: Optimize payload on ALL crash paths (native, ANR, Java exceptions)
            // This ensures consistent size limits regardless of crash type
            val optimized = CrashGrouping.optimizePayload(crashData)

            val (url, payload, headers) = prepareRequest(optimized)

            android.util.Log.d("EnhancedCrashSender", "📤 Sending crash: ${crashData.crashId} (attempt ${attemptNumber + 1}/$MAX_RETRIES)")

            when (val result = networkProvider.post(url, payload, headers)) {
                is NetworkResult.Success -> {
                    // Log the NR response body to detect silent rejections (NR returns HTTP 200
                    // even when it rejects an event — the body contains {"success":true/false})
                    val nrBody = result.body
                    if (nrBody.contains("\"success\":false") || nrBody.contains("\"success\": false")) {
                        android.util.Log.e("EnhancedCrashSender", "❌ NR accepted POST but rejected event! Body: $nrBody | crashId: ${crashData.crashId}")
                    } else {
                        android.util.Log.i("EnhancedCrashSender", "✅ Crash sent successfully: ${crashData.crashId} | NR: $nrBody")
                    }
                    crashStorage.markAsSent(crashData.crashId)
                    // Mirror to debug webhook if configured (best-effort, no retry)
                    if (debugWebhookUrl.isNotEmpty() && debugWebhookUrl != "https://webhook.site/YOUR-TOKEN-HERE") {
                        try {
                            val (_, debugPayload, debugHeaders) = prepareRequest(optimized)
                            val debugResult = networkProvider.post(debugWebhookUrl, debugPayload, debugHeaders)
                            when (debugResult) {
                                is NetworkResult.Success -> android.util.Log.i("EnhancedCrashSender", "🔗 Debug webhook: sent OK → $debugWebhookUrl")
                                is NetworkResult.Failure -> android.util.Log.w("EnhancedCrashSender", "🔗 Debug webhook: failed (${debugResult.error}) — NR send was OK")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("EnhancedCrashSender", "🔗 Debug webhook error (ignored): ${e.message}")
                        }
                    }
                    true
                }
                is NetworkResult.Failure -> {
                    android.util.Log.w("EnhancedCrashSender", "❌ Failed to send crash: ${result.error}")
                    if (attemptNumber < MAX_RETRIES - 1) {
                        val delayMs = calculateBackoff(attemptNumber)
                        android.util.Log.d("EnhancedCrashSender", "🔄 Retrying in ${delayMs}ms...")
                        delay(delayMs)
                        return@withContext sendCrash(crashData, attemptNumber + 1)
                    } else {
                        android.util.Log.e("EnhancedCrashSender", "❌ Max retries exceeded for crash: ${crashData.crashId}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashSender", "Error sending crash: ${crashData.crashId}", e)
            false
        }
    }

    /**
     * Build the flattened crash payload as a single JSON object.
     * Public so the host layer (Unity C#) can pull the EXACT same payload it would otherwise
     * POST — then sign it and send it itself (events.zbd.lol + NR + webhook). Pass an already
     * optimized CrashData (callers below optimize before calling).
     *
     * New Relic Events API requires flat top-level primitives — all nested objects
     * (deviceInfo, appInfo, deviceState, etc.) are flattened here and arrays removed.
     */
    fun buildPayloadObject(crashData: CrashData): com.google.gson.JsonObject {
        val crashJsonObject = gson.toJsonTree(crashData).asJsonObject
        crashJsonObject.addProperty("eventType", "ZBDCrashReport")
        crashJsonObject.addProperty("gameId", crashData.appInfo.packageName)

        // Flatten deviceInfo — New Relic drops the nested object without this
        crashJsonObject.addProperty("deviceModel", crashData.deviceInfo.model)
        crashJsonObject.addProperty("deviceManufacturer", crashData.deviceInfo.manufacturer)
        crashJsonObject.addProperty("androidVersion", crashData.deviceInfo.androidVersion)
        crashJsonObject.addProperty("deviceApiLevel", crashData.deviceInfo.apiLevel)
        crashJsonObject.addProperty("deviceBrand", crashData.deviceInfo.brand)
        crashJsonObject.addProperty("screenWidth", crashData.deviceInfo.screenWidth)
        crashJsonObject.addProperty("screenHeight", crashData.deviceInfo.screenHeight)
        crashJsonObject.remove("deviceInfo")

        // Flatten appInfo
        crashJsonObject.addProperty("appVersion", crashData.appInfo.versionName)
        crashJsonObject.addProperty("appPackageName", crashData.appInfo.packageName)
        crashJsonObject.addProperty("appVersionCode", crashData.appInfo.versionCode)
        crashJsonObject.remove("appInfo")

        // Flatten deviceState
        crashJsonObject.addProperty("batteryLevel", crashData.deviceState.batteryLevel)
        crashJsonObject.addProperty("isCharging", crashData.deviceState.isCharging)
        crashJsonObject.addProperty("availableMemoryMB", crashData.deviceState.availableMemoryMB)
        crashJsonObject.addProperty("totalMemoryMB", crashData.deviceState.totalMemoryMB)
        crashJsonObject.addProperty("lowMemory", crashData.deviceState.lowMemory)
        crashJsonObject.addProperty("orientation", crashData.deviceState.orientation)
        crashJsonObject.remove("deviceState")

        // Flatten networkInfo
        crashJsonObject.addProperty("networkConnected", crashData.networkInfo.isConnected)
        crashJsonObject.addProperty("networkType", crashData.networkInfo.connectionType)
        crashJsonObject.remove("networkInfo")

        // Flatten memoryInfo
        crashJsonObject.addProperty("heapSizeKB", crashData.memoryInfo.heapSizeKB)
        crashJsonObject.addProperty("heapFreeKB", crashData.memoryInfo.heapFreeKB)
        crashJsonObject.remove("memoryInfo")

        // Flatten cpuInfo
        crashJsonObject.addProperty("cpuCores", crashData.cpuInfo.coreCount)
        crashJsonObject.addProperty("cpuArchitecture", crashData.cpuInfo.architecture)
        crashJsonObject.remove("cpuInfo")

        // Flatten processInfo
        crashJsonObject.addProperty("processName", crashData.processInfo.processName)
        crashJsonObject.addProperty("processForeground", crashData.processInfo.foreground)
        crashJsonObject.remove("processInfo")

        // Flatten customData map (key operation fields to top level)
        crashData.customData["currentOperation"]?.let { crashJsonObject.addProperty("currentOperation", it) }
        crashData.customData["lastSuccessfulOperation"]?.let { crashJsonObject.addProperty("lastSuccessfulOperation", it) }
        crashData.customData["lastFailedOperation"]?.let { crashJsonObject.addProperty("lastFailedOperation", it) }
        crashData.customData["lastOperationError"]?.let { crashJsonObject.addProperty("lastOperationError", it) }

        // Remove unsupported types (arrays and nested objects New Relic cannot store)
        crashJsonObject.remove("allThreads")
        crashJsonObject.remove("breadcrumbs")
        crashJsonObject.remove("memoryWarnings")
        crashJsonObject.remove("networkChanges")
        crashJsonObject.remove("nativeRegisters")
        crashJsonObject.remove("operationContext")
        crashJsonObject.remove("sdCardInfo")
        crashJsonObject.remove("diskPerformance")
        crashJsonObject.remove("sessionInfo")
        crashJsonObject.remove("memoryState")
        crashJsonObject.remove("customData")

        return crashJsonObject
    }

    /** Single-crash payload as a JSON string — exactly what the host signs and sends. */
    fun buildPayloadJson(crashData: CrashData): String = gson.toJson(buildPayloadObject(crashData))

    /**
     * Prepare request (uncompressed JSON) — wraps the flattened payload object in the
     * single-element array New Relic's Events API expects.
     */
    private fun prepareRequest(crashData: CrashData): Triple<String, String, Map<String, String>> {
        val url = apiEndpoint
        val jsonArray = com.google.gson.JsonArray()
        jsonArray.add(buildPayloadObject(crashData))
        val json = gson.toJson(jsonArray)

        val headersMap = mutableMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "CrashReporter-Android/2.0",
            "X-Crash-Fingerprint" to crashData.crashFingerprint,
            "X-Crash-Severity" to crashData.severity
        )
        if (apiKey.isNotEmpty()) {
            headersMap["Api-Key"] = apiKey
        }
        return Triple(url, json, headersMap)
    }

    /**
     * Add crash to batch queue (with size limit)
     */
    private suspend fun addToBatch(crashData: CrashData) {
        batchMutex.withLock {
            // If queue exceeds max size, remove oldest crash to prevent unbounded growth
            if (batchQueue.size >= MAX_QUEUE_SIZE) {
                val removed = batchQueue.removeAt(0)
                android.util.Log.w("EnhancedCrashSender", "⚠️ Queue full ($MAX_QUEUE_SIZE max), dropped oldest crash: ${removed.crashId}")
            }

            batchQueue.add(crashData)
            android.util.Log.d("EnhancedCrashSender", "📦 Added to batch (${batchQueue.size}/$BATCH_SIZE)")

            if (batchQueue.size >= BATCH_SIZE || (System.currentTimeMillis() - lastBatchTime) > BATCH_TIMEOUT_MS) {
                flushBatch()
            }
        }
    }

    /**
     * Flush batch queue
     */
    suspend fun flushBatch() {
        batchMutex.withLock {
            if (batchQueue.isEmpty()) return

            val crashes = batchQueue.toList()
            batchQueue.clear()
            lastBatchTime = System.currentTimeMillis()

            android.util.Log.d("EnhancedCrashSender", "📤 Flushing batch of ${crashes.size} crashes")

            // Send batch
            sendBatch(crashes)
        }
    }

    /**
     * Send batch of crashes
     */
    private suspend fun sendBatch(crashes: List<CrashData>) = withContext(Dispatchers.IO) {
        try {
            // Send each crash individually
            crashes.forEach { sendCrash(it) }
        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashSender", "Error sending batch", e)
        }
    }

    /**
     * Log counter update for deduplicated crash (no network call needed)
     */
    private fun sendCounterUpdate(fingerprint: String, count: Int) {
        android.util.Log.d("EnhancedCrashSender", "📊 Crash occurrence #$count for fingerprint: ${fingerprint.take(8)}")
    }

    /**
     * Send all pending crashes from storage with optional rate limiting
     * @param maxPerMinute Max crashes to send per minute (throttle to prevent network spikes)
     *                      Default: 10 per minute = 1 crash every 6 seconds
     */
    suspend fun sendAllPendingCrashes(maxPerMinute: Int = 10) = withContext(Dispatchers.IO) {
        try {
            val pendingFiles = crashStorage.getPendingCrashFiles()

            if (pendingFiles.isEmpty()) {
                android.util.Log.i("EnhancedCrashSender", "No pending crashes to send")
                return@withContext
            }

            android.util.Log.i("EnhancedCrashSender", "📤 Sending ${pendingFiles.size} pending crash(es) at max $maxPerMinute/min")

            // Calculate delay between sends: 60000ms / maxPerMinute
            val delayBetweenSendsMs = 60000 / maxPerMinute

            pendingFiles.forEachIndexed { index, file ->
                try {
                    // Extract crash ID from filename
                    val crashId = file.nameWithoutExtension.removePrefix("crash_")
                    val crashData = crashStorage.loadCrash(crashId)

                    if (crashData != null) {
                        // Crashes loaded from disk bypass deduplication — they were already saved
                        // because a previous send attempt failed. Dedup caused them to be stuck
                        // forever (fingerprint marked "reported" before send, so every retry = IncrementOnly).
                        android.util.Log.i("EnhancedCrashSender", "📡 HTTP POST → New Relic: ${crashData.crashId}")
                        val optimized = CrashGrouping.optimizePayload(crashData)
                        val success = sendCrash(optimized)
                        if (success) {
                            android.util.Log.i("EnhancedCrashSender", "✅ Crash sent successfully: $crashId")
                        } else {
                            android.util.Log.w("EnhancedCrashSender", "❌ Failed to send crash: $crashId (will retry next launch)")
                        }
                    } else {
                        android.util.Log.w("EnhancedCrashSender", "Failed to load crash from ${file.name}")
                    }

                    // Throttle: Add delay between sends (except after last one)
                    if (index < pendingFiles.size - 1) {
                        android.util.Log.d("EnhancedCrashSender", "🔄 Throttling: waiting ${delayBetweenSendsMs}ms before next send...")
                        delay(delayBetweenSendsMs.toLong())
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EnhancedCrashSender", "Error processing crash file ${file.name}", e)
                }
            }

            android.util.Log.i("EnhancedCrashSender", "✅ Finished sending pending crashes")
        } catch (e: Exception) {
            android.util.Log.e("EnhancedCrashSender", "Error in sendAllPendingCrashes", e)
        }
    }

    /**
     * Calculate exponential backoff delay
     * 5s → 10s → 20s → 40s (capped at 60s)
     */
    private fun calculateBackoff(attemptNumber: Int): Long {
        val exponentialDelay = (INITIAL_RETRY_DELAY_MS * 2.0.pow(attemptNumber.toDouble())).toLong()
        return minOf(exponentialDelay, MAX_RETRY_DELAY_MS)
    }
}
