package com.crashreporter.library

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

class CrashHandler(
    private val context: Context,
    private val crashStorage: CrashStorage,
    private val crashSender: CrashSender,
    private val deviceInfoCollector: DeviceInfoCollector
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            android.util.Log.e("CrashHandler", "Uncaught exception detected", throwable)

            val crashData = collectCrashData(thread, throwable)

            runBlocking {
                crashStorage.saveCrash(crashData)
            }

            runBlocking {
                try {
                    crashSender.sendCrash(crashData)
                } catch (e: Exception) {
                    android.util.Log.d("CrashHandler", "Failed to send crash immediately: ${e.message}")
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("CrashHandler", "Error handling crash", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun collectCrashData(thread: Thread, throwable: Throwable): CrashData {
        val timestamp = System.currentTimeMillis()
        val crashId = UUID.randomUUID().toString()

        return CrashData(
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

            // NEW FIELDS
            memoryInfo = deviceInfoCollector.getMemoryInfo(),
            cpuInfo = deviceInfoCollector.getCpuInfo(),
            processInfo = deviceInfoCollector.getProcessInfo(),
            allThreads = getAllThreadStackTraces(),
            breadcrumbs = BreadcrumbManager.getBreadcrumbs(),
            customData = CustomDataManager.getCustomData(),
            environment = CustomDataManager.getEnvironment()
        )
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
            android.util.Log.e("CrashHandler", "Error getting thread info", e)
        }

        return threads
    }
}

// ENHANCED DATA CLASSES

data class CrashData(
    val crashId: String,
    val timestamp: Long,
    val exceptionType: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val threadName: String,
    val deviceInfo: DeviceInfo,
    val appInfo: AppInfo,
    val deviceState: DeviceState,
    val networkInfo: NetworkInfo,

    // NEW FIELDS
    val memoryInfo: MemoryInfo,
    val cpuInfo: CpuInfo,
    val processInfo: ProcessInfo,
    val allThreads: List<ThreadInfo>,
    val breadcrumbs: List<Breadcrumb>,
    val customData: Map<String, String>,
    val environment: String
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val brand: String,
    val device: String,
    val board: String,      // NEW
    val hardware: String,   // NEW
    val screenDensity: Float, // NEW
    val screenWidth: Int,   // NEW
    val screenHeight: Int,  // NEW
    val locale: String      // NEW
)

data class AppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,  // NEW
    val lastUpdateTime: Long,    // NEW
    val targetSdkVersion: Int,   // NEW
    val minSdkVersion: Int       // NEW
)

data class DeviceState(
    val batteryLevel: Float,
    val isCharging: Boolean,
    val availableMemoryMB: Long,
    val totalMemoryMB: Long,
    val availableStorageMB: Long,
    val totalStorageMB: Long,
    val lowMemory: Boolean,       // NEW
    val batteryTemperature: Float, // NEW
    val screenOn: Boolean,        // NEW
    val orientation: String       // NEW
)

data class NetworkInfo(
    val connectionType: String,
    val isConnected: Boolean,
    val networkOperator: String,  // NEW (carrier)
    val isRoaming: Boolean,       // NEW
    val signalStrength: Int       // NEW
)

// NEW DATA CLASSES

data class MemoryInfo(
    val heapSizeKB: Long,
    val heapAllocatedKB: Long,
    val heapFreeKB: Long,
    val nativeHeapSizeKB: Long,
    val nativeHeapAllocatedKB: Long,
    val memoryClass: Int,
    val largeMemoryClass: Int
)

data class CpuInfo(
    val coreCount: Int,
    val architecture: String,
    val cpuUsagePercent: Float
)

data class ProcessInfo(
    val processId: Int,
    val processName: String,
    val importance: String,
    val foreground: Boolean
)

data class ThreadInfo(
    val id: Long,
    val name: String,
    val state: String,
    val priority: Int,
    val isDaemon: Boolean,
    val stackTrace: String
)

data class Breadcrumb(
    val timestamp: Long,
    val category: String,
    val message: String,
    val level: String,
    val data: Map<String, String> = emptyMap()
)