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
        val sdkPatterns = listOf(
            "com.zbd.",
            "ZBD",
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
     * Determine which SDK component is responsible based on stack trace
     */
    @JvmStatic
    fun determineResponsibleComponent(stackTrace: String): String {
        return when {
            stackTrace.contains("ZBDUserController", ignoreCase = true) -> "ZBDUserController"
            stackTrace.contains("ZBDSignUpController", ignoreCase = true) -> "ZBDSignUpController"
            stackTrace.contains("ZBDSendRewardController", ignoreCase = true) -> "ZBDSendRewardController"
            stackTrace.contains("ZBDCrashReporter", ignoreCase = true) -> "ZBDCrashReporter"
            stackTrace.contains("ZBDAndroidCrashBridge", ignoreCase = true) -> "ZBDAndroidCrashBridge"
            stackTrace.contains("crashreporter.library", ignoreCase = true) -> "CrashReporterLibrary"
            stackTrace.contains("ZBD", ignoreCase = true) -> "ZBD_Unknown"
            else -> ""
        }
    }
}
