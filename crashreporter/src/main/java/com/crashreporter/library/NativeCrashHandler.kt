package com.crashreporter.library

import android.content.Context
import java.io.File

/**
 * JNI Bridge to native crash handler
 * Handles native crashes (SIGSEGV, SIGABRT, SIGFPE, etc.)
 */
object NativeCrashHandler {

    private var isNativeInitialized = false
    private lateinit var crashDir: File

    // Load native library
    init {
        try {
            System.loadLibrary("crashreporter-native")
            android.util.Log.i("NativeCrashHandler", "Native library loaded successfully")
        } catch (e: Exception) {
            android.util.Log.e("NativeCrashHandler", "Failed to load native library", e)
        }
    }

    /**
     * Initialize native crash handler
     */
    fun initialize(context: Context) {
        if (isNativeInitialized) {
            android.util.Log.w("NativeCrashHandler", "Native crash handler already initialized")
            return
        }

        try {
            crashDir = File(context.filesDir, "crashes")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }

            // Call native initialization
            initialize(crashDir.absolutePath)
            isNativeInitialized = true

            android.util.Log.i("NativeCrashHandler", "Native crash handler initialized")
        } catch (e: Exception) {
            android.util.Log.e("NativeCrashHandler", "Failed to initialize native crash handler", e)
        }
    }

    /**
     * Check if there's a pending native crash from previous session
     */
    fun getPendingNativeCrash(): File? {
        if (!::crashDir.isInitialized) {
            return null
        }

        val nativeCrashFile = File(crashDir, "native_crash.txt")
        return if (nativeCrashFile.exists() && nativeCrashFile.length() > 0) {
            nativeCrashFile
        } else {
            null
        }
    }

    /**
     * Delete native crash file after successful processing
     */
    fun deleteNativeCrashFile() {
        if (!::crashDir.isInitialized) {
            return
        }

        val nativeCrashFile = File(crashDir, "native_crash.txt")
        if (nativeCrashFile.exists()) {
            nativeCrashFile.delete()
            android.util.Log.i("NativeCrashHandler", "Native crash file deleted")
        }
    }

    /**
     * Trigger a native crash for testing purposes
     * @param type 0=SIGSEGV, 1=SIGABRT, 2=SIGFPE, 3=Invalid memory, 4=Stack overflow
     */
    fun triggerTestCrash(type: Int) {
        android.util.Log.w("NativeCrashHandler", "Triggering test native crash type: $type")
        triggerNativeCrash(type)
    }

    // Native methods
    private external fun initialize(crashDir: String)
    private external fun triggerNativeCrash(type: Int)
    external fun isInitialized(): Boolean
}
