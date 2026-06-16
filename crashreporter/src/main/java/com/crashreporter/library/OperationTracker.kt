package com.crashreporter.library

/**
 * Tracks SDK operations and context for crash reporting
 * Used by Unity bridge to record what operation was in progress when crash occurred
 * Also stores SDK context (version, component, etc.) for SLO monitoring
 */
object OperationTracker {

    // MARK: - SDK Context (Common SLO fields)
    @Volatile
    private var sdkVersion: String = ""

    @Volatile
    private var crashReporterPluginVersion: String = "1.0.0"

    @Volatile
    private var platform: String = "Android"

    @Volatile
    private var initFailurePoint: String = ""

    @Volatile
    private var responsibleComponent: String = ""

    // MARK: - Operation Tracking
    @Volatile
    private var currentOperation: String? = null

    @Volatile
    private var lastSuccessfulOperation: String? = null

    @Volatile
    private var lastFailedOperation: String? = null

    @Volatile
    private var lastFailureReason: String? = null

    @Volatile
    private var operationContext: MutableMap<String, String> = mutableMapOf()

    /**
     * Set the current operation in progress
     */
    @JvmStatic
    fun setCurrentOperation(operation: String?) {
        currentOperation = operation
        android.util.Log.d("OperationTracker", "Current operation: $operation")
    }

    /**
     * Get the current operation in progress
     */
    @JvmStatic
    fun getCurrentOperation(): String? = currentOperation

    /**
     * Record a successful operation
     */
    @JvmStatic
    fun setLastSuccessfulOperation(operation: String) {
        lastSuccessfulOperation = operation
        android.util.Log.d("OperationTracker", "Successful operation: $operation")
    }

    /**
     * Get the last successful operation
     */
    @JvmStatic
    fun getLastSuccessfulOperation(): String? = lastSuccessfulOperation

    /**
     * Record a failed operation
     */
    @JvmStatic
    fun setLastFailedOperation(operation: String, reason: String) {
        lastFailedOperation = operation
        lastFailureReason = reason
        android.util.Log.d("OperationTracker", "Failed operation: $operation - $reason")
    }

    /**
     * Get the last failed operation
     */
    @JvmStatic
    fun getLastFailedOperation(): String? = lastFailedOperation

    /**
     * Get the last failure reason
     */
    @JvmStatic
    fun getLastFailureReason(): String? = lastFailureReason

    /**
     * Clear all tracked operations
     */
    @JvmStatic
    fun clear() {
        currentOperation = null
        lastSuccessfulOperation = null
        lastFailedOperation = null
        lastFailureReason = null
    }

    /**
     * Get all tracked data as a map for crash reports
     */
    @JvmStatic
    fun toMap(): Map<String, String?> {
        return mapOf(
            "currentOperation" to currentOperation,
            "lastSuccessfulOperation" to lastSuccessfulOperation,
            "lastFailedOperation" to lastFailedOperation,
            "lastFailureReason" to lastFailureReason
        )
    }

    // MARK: - SDK Context Methods

    /**
     * Set the ZBD SDK version (called from Unity)
     */
    @JvmStatic
    fun setSDKVersion(version: String) {
        sdkVersion = version
        android.util.Log.d("OperationTracker", "SDK Version set: $version")
    }

    /**
     * Get the ZBD SDK version
     */
    @JvmStatic
    fun getSDKVersion(): String = sdkVersion

    /**
     * Set the crash reporter plugin version
     */
    @JvmStatic
    fun setCrashReporterPluginVersion(version: String) {
        crashReporterPluginVersion = version
        android.util.Log.d("OperationTracker", "Crash Reporter Plugin Version set: $version")
    }

    /**
     * Get the crash reporter plugin version
     */
    @JvmStatic
    fun getCrashReporterPluginVersion(): String = crashReporterPluginVersion

    /**
     * Set the platform (Android, iOS, Unity)
     */
    @JvmStatic
    fun setPlatform(platformName: String) {
        platform = platformName
        android.util.Log.d("OperationTracker", "Platform set: $platformName")
    }

    /**
     * Get the platform
     */
    @JvmStatic
    fun getPlatform(): String = platform

    /**
     * Set the SDK component that caused the crash (for SDK-related crashes)
     */
    @JvmStatic
    fun setResponsibleComponent(component: String) {
        responsibleComponent = component
        android.util.Log.d("OperationTracker", "Responsible component set: $component")
    }

    /**
     * Get the responsible SDK component
     */
    @JvmStatic
    fun getResponsibleComponent(): String = responsibleComponent

    /**
     * Set where in SDK init the failure occurred (if applicable)
     */
    @JvmStatic
    fun setInitFailurePoint(failurePoint: String) {
        initFailurePoint = failurePoint
        android.util.Log.d("OperationTracker", "Init failure point set: $failurePoint")
    }

    /**
     * Get the init failure point
     */
    @JvmStatic
    fun getInitFailurePoint(): String = initFailurePoint

    /**
     * Set additional context for the current operation
     */
    @JvmStatic
    fun setOperationContext(key: String, value: String) {
        operationContext[key] = value
    }

    /**
     * Get operation context
     */
    @JvmStatic
    fun getOperationContext(): Map<String, String> = operationContext.toMap()

    /**
     * Clear operation context
     */
    @JvmStatic
    fun clearOperationContext() {
        operationContext.clear()
    }

    /**
     * Check if crash is related to ZBD SDK based on stack trace analysis
     */
    @JvmStatic
    fun isSDKRelatedCrash(stackTrace: String): Boolean {
        // If an SDK operation was active at crash time, it's SDK-related.
        // Stack trace patterns alone don't work for IL2CPP release builds
        // since C# class names are stripped from the native stack trace.
        if (!getCurrentOperation().isNullOrEmpty()) return true
        if (!getLastFailedOperation().isNullOrEmpty()) return true

        // Fallback: check stack trace patterns (works for JVM/debug builds).
        // NOTE: use "ZBD." (the C# namespace prefix), NOT bare "ZBD" — the app's package
        // path (e.g. "com.zbdpay.sdkdemo") appears in every native stack and a bare "ZBD"
        // would match "zbdpay", falsely flagging EVERY native crash as SDK-related.
        val sdkPatterns = listOf(
            "com.zbd.",
            "ZBD.",
            "ZBDSDK",
            "ZBDUserController",
            "ZBDSignUpController",
            "ZBDSendRewardController",
            "ZBDCrashReporter",
            "ZBDAndroidCrashBridge",
            "crashreporter.library"
        )
        return sdkPatterns.any { stackTrace.contains(it, ignoreCase = true) }
    }

    /**
     * Extract the faulting library (.so) from a native crash stack trace.
     * Native frames look like: "#003 pc 0x... /data/.../libXXX.so (symbol+0x..)".
     * Skips our own crash-handler frames and the libc trampoline frames to find the
     * first frame that represents the actual faulting code.
     * Returns "" for managed/ANR stacks (which have no native library frames).
     */
    @JvmStatic
    fun extractFaultingLibrary(stackTrace: String): String {
        // Signal/runtime plumbing sits on top of the stack during a crash — skip it all so
        // we report the first *app* library where the crash actually originated
        // (e.g. libil2cpp.so = managed C#, libunity.so = engine, or the game's own .so).
        val skip = listOf(
            "libcrashreporter-native.so",  // our own signal handler (always on the stack)
            "libsigchain.so",              // ART signal-chain trampoline
            "libart.so",                   // ART runtime / interpreter→JNI bridge
            "libc.so",
            "libc++.so",
            "libc++_shared.so",
            "libc++abi.so",
            "libnativehelper.so",
            "libdl.so"
        )
        stackTrace.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("#")) return@forEach
            val soToken = trimmed.split(' ', '(', ')').firstOrNull { it.contains(".so") }
                ?: return@forEach
            val base = soToken.substringAfterLast('/')
            if (skip.none { base.equals(it, ignoreCase = true) }) {
                return base
            }
        }
        return ""
    }

    /**
     * Compute confidence that this crash was caused by ZBD SDK code.
     *  - "high":   ZBD's own native library faulted, or a real ZBD symbol is present (debug/unstripped builds)
     *  - "medium": a ZBD operation was ACTIVELY in-flight when the crash happened
     *  - "low":    a ZBD operation had recently failed but already ended — weak/coincidental signal
     *  - "none":   no ZBD signal — most likely game or engine code
     *
     * IMPORTANT: in IL2CPP release builds, ZBD C# code and the game's C# code share
     * libil2cpp.so with stripped symbols, so "high" is NOT reachable for managed C#
     * crashes on-device. Trustworthy ZBD-vs-game attribution for those needs backend
     * symbolication. Treat medium/low as hints, not facts.
     */
    @JvmStatic
    fun getSDKConfidence(stackTrace: String, faultingLibrary: String): String {
        // HIGH — our own native library faulted (definitive)
        if (faultingLibrary.equals("libcrashreporter-native.so", ignoreCase = true)) return "high"

        // HIGH — a real ZBD symbol/class name is present (works on debug/unstripped builds)
        val strongPatterns = listOf(
            "com.zbd.",
            "ZBD.",
            "ZBDSDK",
            "ZBDUserController",
            "ZBDSignUpController",
            "ZBDSendRewardController",
            "ZBDCrashReporter",
            "ZBDAndroidCrashBridge",
            "crashreporter.library"
        )
        if (strongPatterns.any { stackTrace.contains(it, ignoreCase = true) }) return "high"

        // MEDIUM — a ZBD operation was actively running (in-flight) at crash time
        if (!getCurrentOperation().isNullOrEmpty()) return "medium"

        // LOW — a ZBD operation had recently failed but already ended (timing coincidence)
        if (!getLastFailedOperation().isNullOrEmpty()) return "low"

        // NONE — no ZBD signal
        return "none"
    }

    /**
     * Determine which SDK component is responsible based on stack trace
     */
    @JvmStatic
    fun determineResponsibleComponent(stackTrace: String): String {
        // First try to identify component from stack trace (works for JVM/debug builds)
        val fromStack = when {
            stackTrace.contains("ZBDUserController", ignoreCase = true) -> "ZBDUserController"
            stackTrace.contains("ZBDSignUpController", ignoreCase = true) -> "ZBDSignUpController"
            stackTrace.contains("ZBDSendRewardController", ignoreCase = true) -> "ZBDSendRewardController"
            stackTrace.contains("ZBDCrashReporter", ignoreCase = true) -> "ZBDCrashReporter"
            stackTrace.contains("ZBDAndroidCrashBridge", ignoreCase = true) -> "ZBDAndroidCrashBridge"
            stackTrace.contains("crashreporter.library", ignoreCase = true) -> "CrashReporterLibrary"
            stackTrace.contains("ZBD.", ignoreCase = true) -> "ZBD_Unknown"  // "ZBD." namespace, not bare "ZBD" (avoids matching "zbdpay" package path)
            else -> ""
        }
        if (fromStack.isNotEmpty()) return fromStack

        // Fallback for IL2CPP builds: use active operation name as the component
        val op = getCurrentOperation() ?: getLastFailedOperation()
        return if (!op.isNullOrEmpty()) "SDK_$op" else ""
    }
}
