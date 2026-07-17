package com.crashreporter.library

import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Crash Grouping / Fingerprinting with Cost Control
 *
 * Features:
 * - Fingerprinting: Groups similar crashes together
 * - Deduplication: Tracks seen fingerprints, skips duplicates (with persistence!)
 * - Sampling: Sends 100% fatal, 15% non-fatal crashes
 * - Payload limits: Stack trace, threads, breadcrumbs
 * - Persistent storage: Survives app crashes
 */
object CrashGrouping {

    // MARK: - Cost Control Configuration

    /** Sampling rate for non-fatal crashes (15% default, configurable) */
    private var nonFatalSampleRate = 0.15f

    /** Max stack trace lines */
    const val MAX_STACK_TRACE_LINES = 100

    /** Max threads to include */
    const val MAX_THREADS = 5

    /** Max breadcrumbs */
    const val MAX_BREADCRUMBS = 20

    /** Max string length (New Relic 4KB limit) */
    const val MAX_STRING_LENGTH = 4000

    // MARK: - Deduplication State

    /** Fingerprints seen this session -> occurrence count */
    private val sessionFingerprints = ConcurrentHashMap<String, AtomicInteger>()

    /** Fingerprints already fully reported this session (in-memory only) */
    private val reportedFingerprints = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Persistent storage for fingerprints (survives crashes!) */
    private var persistentStorage: FingerprintStorage? = null

    /** Sensitive data patterns to scrub */
    private val sensitivePatterns = listOf(
        Regex("(password|passwd|pwd|secret|token|api[_-]?key|auth)[\"']?\\s*[:=]\\s*[\"']?[^\"'\\s,}]+", RegexOption.IGNORE_CASE),
        Regex("(bearer|authorization)\\s+[^\\s]+", RegexOption.IGNORE_CASE),
        Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b")
    )

    // MARK: - Statistics
    private var totalReceived = AtomicInteger(0)
    private var totalSent = AtomicInteger(0)
    private var deduplicated = AtomicInteger(0)
    private var sampledOut = AtomicInteger(0)

    /**
     * Generate a crash fingerprint/signature
     * Similar crashes will have the same fingerprint
     */
    fun generateFingerprint(crashData: CrashData): String {
        val components = mutableListOf<String>()

        // 1. Exception type (critical)
        components.add(crashData.exceptionType)

        // 2. Top N frames of stack trace (most important)
        val stackFrames = extractStackFrames(crashData.stackTrace)
        if (stackFrames.isNotEmpty()) {
            components.addAll(stackFrames.take(5))  // Top 5 frames
        } else {
            // No parseable frames (e.g. a location-less C# exception). Without this, EVERY
            // stackless crash of the same type would hash to the same fingerprint and dedup would
            // drop distinct bugs. Mix in stable, low-cardinality context so they stay distinct,
            // using a normalized message so volatile content (ids/addresses) can't over-split.
            components.add(normalizeMessage(crashData.exceptionMessage))
            if (crashData.responsibleSDKComponent.isNotBlank()) components.add(crashData.responsibleSDKComponent)
            if (crashData.currentOperation.isNotBlank()) components.add(crashData.currentOperation)
        }

        // 3. Create signature
        val signature = components.joinToString("|")

        // 4. Hash to create short fingerprint
        return hashSignature(signature)
    }

    /** Strip volatile content (uuids, hex addresses, bare numbers) so a message is a stable key. */
    private fun normalizeMessage(message: String): String {
        return message
            .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "UUID")
            .replace(Regex("0x[0-9a-fA-F]+"), "0xADDR")
            .replace(Regex("\\d+"), "N")
            .take(120)
    }

    /**
     * Extract meaningful stack frames from stack trace
     */
    private fun extractStackFrames(stackTrace: String): List<String> {
        val frames = mutableListOf<String>()

        stackTrace.split("\n").forEach { line ->
            // Extract method name and class
            val trimmed = line.trim()

            // Handle different stack trace formats
            when {
                // Java/Kotlin format: "at com.example.MyClass.myMethod(MyClass.kt:42)"
                trimmed.startsWith("at ") -> {
                    val methodPart = trimmed.removePrefix("at ").substringBefore("(")
                    // Remove line numbers and file info, keep class.method
                    frames.add(methodPart)
                }
                // Native format: "#00 pc 0x7f8a9b3c4d libunity.so (MyClass::myMethod+0x1c)"
                trimmed.startsWith("#") -> {
                    val methodPart = trimmed.substringAfter("(").substringBefore("+")
                    if (methodPart.isNotBlank()) {
                        frames.add(methodPart)
                    }
                }
                // Unity C#/IL2CPP format: "Class.Method (args) (at File.cs:42)"
                trimmed.contains("(at ") -> {
                    frames.add(trimmed.substringBefore("(at ").trim())
                }
                // Unity C#/IL2CPP format: "Class.Method () [0x00000] in File.cs:0"
                trimmed.contains("] in ") -> {
                    frames.add(trimmed.substringBefore("[0x").substringBefore("] in ").trim())
                }
            }
        }

        return frames.filter { it.isNotBlank() }
    }

    /**
     * Hash signature to create short fingerprint
     */
    private fun hashSignature(signature: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(signature.toByteArray())
            // Convert to hex string (first 16 chars = 64 bits)
            hash.take(8).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback: use hashCode
            signature.hashCode().toString()
        }
    }

    /**
     * Generate a human-readable issue title
     */
    fun generateIssueTitle(crashData: CrashData): String {
        // Extract first meaningful stack frame
        val stackFrames = extractStackFrames(crashData.stackTrace)
        val topFrame = stackFrames.firstOrNull() ?: "Unknown"

        // Format: "ExceptionType at Location"
        val exceptionType = crashData.exceptionType.substringAfterLast(".")
        return "$exceptionType at $topFrame"
    }

    /**
     * Determine crash severity based on crash data
     */
    fun determineSeverity(crashData: CrashData): CrashSeverity {
        return when {
            // Critical: Main thread crash
            crashData.threadName == "main" -> CrashSeverity.CRITICAL

            // Critical: Native crashes
            crashData.exceptionType.startsWith("SIG") -> CrashSeverity.CRITICAL

            // Critical: OutOfMemoryError
            crashData.exceptionType.contains("OutOfMemoryError") -> CrashSeverity.CRITICAL

            // High: NullPointerException
            crashData.exceptionType.contains("NullPointerException") -> CrashSeverity.HIGH

            // High: IllegalStateException
            crashData.exceptionType.contains("IllegalStateException") -> CrashSeverity.HIGH

            // Medium: Other exceptions
            else -> CrashSeverity.MEDIUM
        }
    }

    /**
     * Check if two crashes are the same issue
     */
    fun areSameIssue(crash1: CrashData, crash2: CrashData): Boolean {
        return generateFingerprint(crash1) == generateFingerprint(crash2)
    }

    // MARK: - Cost Control Methods

    /**
     * Initialize persistent fingerprint storage (call from crash reporter init)
     */
    @JvmStatic
    fun initialize(context: android.content.Context) {
        if (persistentStorage == null) {
            persistentStorage = FingerprintStorage(context)
            android.util.Log.i("CrashGrouping", "✅ Fingerprint storage initialized")
        }
    }

    /**
     * Decide whether to send crash based on deduplication and sampling
     */
    fun shouldSendCrash(crashData: CrashData): SendDecision {
        val fingerprint = crashData.crashFingerprint.ifEmpty { generateFingerprint(crashData) }
        val isFatal = isFatalCrash(crashData)

        // CRITICAL: Check persistent storage first (survives app crashes!)
        persistentStorage?.let { storage ->
            if (storage.wasRecentlyReported(fingerprint)) {
                val count = trackFingerprint(fingerprint)
                return SendDecision.IncrementOnly(fingerprint, count)
            }
        }

        // Deduplication check (session-only)
        val count = trackFingerprint(fingerprint)
        if (count > 1 && isAlreadyReported(fingerprint)) {
            return SendDecision.IncrementOnly(fingerprint, count)
        }

        // Sampling for non-fatal
        if (!isFatal && Random.nextFloat() > nonFatalSampleRate) {
            return SendDecision.Skip("Sampled out (${(nonFatalSampleRate * 100).toInt()}% rate)")
        }

        markAsReported(fingerprint)

        // Save to persistent storage (survives crashes!)
        persistentStorage?.markAsReported(fingerprint)

        return if (isFatal) SendDecision.SendImmediately else SendDecision.AddToBatch
    }

    /**
     * Dedup gate for the host-driven path. Returns true if this fingerprint was already reported
     * (24h persistent window or this session) so the caller skips it; marks it when new. Does not
     * sample — every distinct crash is kept, only exact repeats collapse. Native crashes bypass.
     */
    @JvmStatic
    fun isRecentDuplicate(fingerprint: String): Boolean {
        if (fingerprint.isEmpty()) return false

        // Count EVERY occurrence (including suppressed duplicates) so the crash we do send can
        // carry "seen N times" — without changing which crashes are stored/sent.
        persistentStorage?.incrementCount(fingerprint)

        persistentStorage?.let { storage ->
            if (storage.wasRecentlyReported(fingerprint)) {
                trackFingerprint(fingerprint)
                return true
            }
        }

        val count = trackFingerprint(fingerprint)
        if (count > 1 && isAlreadyReported(fingerprint)) {
            return true
        }

        markAsReported(fingerprint)
        persistentStorage?.markAsReported(fingerprint)
        return false
    }

    /** Occurrence count for a fingerprint within the dedup window (min 1 so a lone crash reads 1). */
    @JvmStatic
    fun getOccurrenceCount(fingerprint: String): Int {
        val c = persistentStorage?.getOccurrenceCount(fingerprint) ?: 0
        return if (c > 0) c else 1
    }

    /**
     * Check if crash is fatal (app will terminate)
     */
    fun isFatalCrash(crashData: CrashData): Boolean {
        return crashData.isNativeCrash ||
                crashData.exceptionType.startsWith("SIG") ||
                crashData.threadName == "main" ||
                crashData.exceptionType.contains("OutOfMemoryError") ||
                crashData.isANR ||
                crashData.isStartupCrash ||
                crashData.severity == "CRITICAL"
    }

    private fun trackFingerprint(fingerprint: String): Int {
        // API 21 compatible: use get() + put() instead of computeIfAbsent()
        val existing = sessionFingerprints[fingerprint]
        val counter = existing ?: AtomicInteger(0)
        if (existing == null) {
            sessionFingerprints[fingerprint] = counter
        }
        return counter.incrementAndGet()
    }

    private fun isAlreadyReported(fingerprint: String): Boolean {
        return reportedFingerprints.contains(fingerprint)
    }

    private fun markAsReported(fingerprint: String) {
        reportedFingerprints.add(fingerprint)
    }

    /**
     * Optimize payload to reduce size
     */
    fun optimizePayload(crashData: CrashData): CrashData {
        return crashData.copy(
            // Scrub BEFORE the size cap — stack traces & logcat are the fields most likely to carry
            // tokens/user-ids/secrets, and this is exactly the payload that leaves the device.
            stackTrace = scrubText(limitStackTrace(crashData.stackTrace)).let { st ->
                // New Relic silently rejects events with any string attribute > 4096 bytes.
                // Cap stack trace at 3985 chars to stay safely under the limit.
                if (st.length > 3985) st.take(3970) + "\n... [truncated]" else st
            },
            allThreads = limitThreads(crashData.allThreads, crashData.threadName),
            breadcrumbs = crashData.breadcrumbs.takeLast(MAX_BREADCRUMBS),
            memoryWarnings = crashData.memoryWarnings.takeLast(10),
            networkChanges = crashData.networkChanges.takeLast(10),
            customData = crashData.customData.entries.take(20).associate { it.key to scrubText(it.value) },
            exceptionMessage = scrubText(crashData.exceptionMessage),
            memoryDump = crashData.memoryDump.take(1000),
            // New Relic rejects attribute values > 4096 bytes — scrub then cap recentLogcat
            recentLogcat = scrubText(crashData.recentLogcat).let { lc ->
                if (lc.length > 4000) lc.take(3985) + " [truncated]" else lc
            }
        )
    }

    private fun limitStackTrace(stackTrace: String): String {
        val lines = stackTrace.lines()
        return if (lines.size > MAX_STACK_TRACE_LINES) {
            lines.take(MAX_STACK_TRACE_LINES).joinToString("\n") + "\n... [${lines.size - MAX_STACK_TRACE_LINES} more lines truncated]"
        } else stackTrace
    }

    private fun limitThreads(threads: List<ThreadInfo>, crashedThread: String): List<ThreadInfo> {
        if (threads.size <= MAX_THREADS) return threads
        val crashed = threads.find { it.name == crashedThread }
        val others = threads.filter { it.name != crashedThread }
            .sortedByDescending { it.name == "main" }
            .take(MAX_THREADS - 1)
        return listOfNotNull(crashed) + others
    }

    private fun scrubText(text: String): String {
        var result = text
        sensitivePatterns.forEach { pattern -> result = pattern.replace(result, "[REDACTED]") }
        return if (result.length > MAX_STRING_LENGTH) result.take(MAX_STRING_LENGTH - 15) + " [truncated]" else result
    }

    fun getStats(): String {
        return "Received: ${totalReceived.get()}, Sent: ${totalSent.get()}, Deduplicated: ${deduplicated.get()}, Sampled: ${sampledOut.get()}"
    }

    fun resetSession() {
        sessionFingerprints.clear()
        reportedFingerprints.clear()
    }

    /**
     * Set non-fatal crash sampling rate (0.0 = 0%, 1.0 = 100%)
     * @param rate Sampling rate between 0.0 and 1.0
     */
    fun setNonFatalSamplingRate(rate: Float) {
        nonFatalSampleRate = rate.coerceIn(0.0f, 1.0f)
        android.util.Log.d("CrashGrouping", "Non-fatal sampling rate set to ${(nonFatalSampleRate * 100).toInt()}%")
    }

    /**
     * Get current non-fatal crash sampling rate
     */
    fun getNonFatalSamplingRate(): Float {
        return nonFatalSampleRate
    }
}

enum class CrashSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Decision for what to do with a crash
 */
sealed class SendDecision {
    object SendImmediately : SendDecision()
    object AddToBatch : SendDecision()
    data class Skip(val reason: String) : SendDecision()
    data class IncrementOnly(val fingerprint: String, val count: Int) : SendDecision()
}
