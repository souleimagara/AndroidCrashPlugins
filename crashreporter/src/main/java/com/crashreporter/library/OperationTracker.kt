package com.crashreporter.library

/**
 * Tracks SDK operations for crash context
 * Used by Unity bridge to record what operation was in progress when crash occurred
 */
object OperationTracker {

    @Volatile
    private var currentOperation: String? = null

    @Volatile
    private var lastSuccessfulOperation: String? = null

    @Volatile
    private var lastFailedOperation: String? = null

    @Volatile
    private var lastFailureReason: String? = null

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
}
