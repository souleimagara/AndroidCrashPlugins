package com.crashreporter.library

import java.util.UUID

/**
 * Manages user session tracking for crash context
 * Used by Unity bridge to track session information
 */
object SessionManager {

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var sessionStartTime: Long = 0

    @Volatile
    private var userId: String? = null

    @Volatile
    private var isActive: Boolean = false

    /**
     * Start a new session
     */
    @JvmStatic
    fun startSession(userId: String? = null) {
        sessionId = UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()
        this.userId = userId
        isActive = true
        android.util.Log.d("SessionManager", "Session started: $sessionId")
    }

    /**
     * End the current session
     */
    @JvmStatic
    fun endSession() {
        isActive = false
        android.util.Log.d("SessionManager", "Session ended: $sessionId")
    }

    /**
     * Get the current session ID
     */
    @JvmStatic
    fun getSessionId(): String? = sessionId

    /**
     * Get session duration in milliseconds
     */
    @JvmStatic
    fun getSessionDuration(): Long {
        return if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0
        }
    }

    /**
     * Set the user ID for this session
     */
    @JvmStatic
    fun setUserId(userId: String?) {
        this.userId = userId
    }

    /**
     * Get the user ID for this session
     */
    @JvmStatic
    fun getUserId(): String? = userId

    /**
     * Check if a session is active
     */
    @JvmStatic
    fun isSessionActive(): Boolean = isActive

    /**
     * Clear session data
     */
    @JvmStatic
    fun clear() {
        sessionId = null
        sessionStartTime = 0
        userId = null
        isActive = false
    }

    /**
     * Get all session data as a map for crash reports
     */
    @JvmStatic
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "sessionId" to sessionId,
            "sessionDuration" to getSessionDuration(),
            "userId" to userId,
            "isActive" to isActive,
            "sessionStartTime" to sessionStartTime
        )
    }
}
