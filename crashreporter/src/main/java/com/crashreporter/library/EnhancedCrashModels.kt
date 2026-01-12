package com.crashreporter.library

/**
 * Enhanced Crash Data Models with ALL new fields
 * Includes: Fingerprinting, ANR detection, Startup crashes, Device fields, etc.
 */

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
    val memoryInfo: MemoryInfo,
    val cpuInfo: CpuInfo,
    val processInfo: ProcessInfo,
    val allThreads: List<ThreadInfo>,
    val breadcrumbs: List<Breadcrumb>,
    val customData: Map<String, String>,
    val environment: String,

    // NEW FIELDS - Crash Grouping
    val crashFingerprint: String = "",  // SHA-256 hash for grouping
    val issueTitle: String = "",        // Human-readable crash title
    val severity: String = "",          // CRITICAL, HIGH, MEDIUM, LOW

    // NEW FIELDS - ANR Detection
    val isANR: Boolean = false,
    val anrDurationMs: Long = 0,

    // NEW FIELDS - Startup Crash Detection
    val isStartupCrash: Boolean = false,
    val isInCrashLoop: Boolean = false,
    val crashLoopCount: Int = 0,

    // NEW FIELDS - Additional Device Info
    val powerSaveMode: Boolean = false,
    val developerMode: Boolean = false,
    val isDebugBuild: Boolean = false,
    val bootTime: Long = 0,
    val deviceUptime: Long = 0,
    val timezone: String = "",

    // NEW FIELDS - Native Crash Data (populated for native crashes)
    val isNativeCrash: Boolean = false,
    val nativeSignal: String = "",
    val nativeFaultAddress: String = "",
    val nativeRegisters: Map<String, String> = emptyMap(),
    val memoryDump: String = "",
    val recentLogcat: String = "",  // Last 50 lines of logcat (max 5KB)

    // NEW FIELDS - Memory Warnings & Network Tracking
    val memoryWarnings: List<MemoryWarning> = emptyList(),
    val memoryPressure: String = "UNKNOWN",
    val networkChanges: List<NetworkChange> = emptyList(),
    val wasNetworkRecentlyLost: Boolean = false,

    // NEW FIELDS - Additional Device Info
    val isVPNActive: Boolean = false,
    val isProxyActive: Boolean = false,
    val sdCardInfo: SDCardInfo? = null,
    val diskPerformance: DiskPerformance? = null,

    // NEW FIELDS - SDK Context (Common SLO fields)
    val sdkVersion: String = "",                    // ZBD SDK version (e.g., "0.7.0")
    val crashReporterPluginVersion: String = "1.0.0", // Crash reporter plugin version
    val platform: String = "Android",               // Platform: Android, iOS, Unity
    val isSDKRelated: Boolean = false,              // Is crash related to ZBD SDK code
    val responsibleSDKComponent: String = "",       // Which SDK component caused crash (e.g., "ZBDUserController", "ZBDCrashReporter")
    val initFailurePoint: String = "",              // Where in SDK init the crash occurred (if applicable)
    val currentOperation: String = "",              // What SDK operation was running when crash happened
    val operationContext: Map<String, String> = emptyMap() // Additional context about the operation
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val brand: String,
    val device: String,
    val board: String,
    val hardware: String,
    val screenDensity: Float,
    val screenWidth: Int,
    val screenHeight: Int,
    val locale: String
)

data class AppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val targetSdkVersion: Int,
    val minSdkVersion: Int
)

data class DeviceState(
    val batteryLevel: Float,
    val isCharging: Boolean,
    val availableMemoryMB: Long,
    val totalMemoryMB: Long,
    val availableStorageMB: Long,
    val totalStorageMB: Long,
    val lowMemory: Boolean,
    val batteryTemperature: Float,
    val screenOn: Boolean,
    val orientation: String
)

data class NetworkInfo(
    val connectionType: String,
    val isConnected: Boolean,
    val networkOperator: String,
    val isRoaming: Boolean,
    val signalStrength: Int
)

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

data class SDCardInfo(
    val hasSDCard: Boolean,
    val path: String,
    val totalMB: Long,
    val availableMB: Long
)

data class DiskPerformance(
    val readSpeedMBps: Double,
    val writeSpeedMBps: Double,
    val testTimestamp: Long
)
