package com.crashreporter.library

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone
import android.app.ActivityManager.MemoryInfo as AndroidMemoryInfo

/**
 * Enhanced Device Info Collector with ALL missing fields
 * Now includes: PowerSaveMode, DeveloperMode, DebugBuild, BootTime, Uptime, Timezone, etc.
 */
class EnhancedDeviceInfoCollector(private val context: Context) {

    fun getDeviceInfo(): DeviceInfo {
        val displayMetrics = context.resources.displayMetrics

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            brand = Build.BRAND,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            screenDensity = displayMetrics.density,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            locale = Locale.getDefault().toString()
        )
    }

    fun getAppInfo(): AppInfo {
        return try {
            val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val appInfo: ApplicationInfo = packageInfo.applicationInfo

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            AppInfo(
                packageName = context.packageName,
                versionName = packageInfo.versionName ?: "Unknown",
                versionCode = versionCode,
                firstInstallTime = packageInfo.firstInstallTime,
                lastUpdateTime = packageInfo.lastUpdateTime,
                targetSdkVersion = appInfo.targetSdkVersion,
                minSdkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appInfo.minSdkVersion
                } else {
                    21
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfo", "Error getting app info", e)
            AppInfo(
                packageName = context.packageName,
                versionName = "Unknown",
                versionCode = 0,
                firstInstallTime = 0,
                lastUpdateTime = 0,
                targetSdkVersion = 0,
                minSdkVersion = 21
            )
        }
    }

    fun getDeviceState(): DeviceState {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val orientation = context.resources.configuration.orientation

        return DeviceState(
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            availableMemoryMB = getAvailableMemoryMB(),
            totalMemoryMB = getTotalMemoryMB(),
            availableStorageMB = getAvailableStorageMB(),
            totalStorageMB = getTotalStorageMB(),
            lowMemory = isLowMemory(),
            batteryTemperature = getBatteryTemperature(),
            screenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            },
            orientation = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                "portrait"
            } else {
                "landscape"
            }
        )
    }

    fun getNetworkInfo(): NetworkInfo {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)

                if (capabilities == null) {
                    NetworkInfo(
                        connectionType = "NONE",
                        isConnected = false,
                        networkOperator = telephonyManager?.networkOperatorName ?: "Unknown",
                        isRoaming = telephonyManager?.isNetworkRoaming ?: false,
                        signalStrength = -1
                    )
                } else {
                    val connectionType = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        else -> "OTHER"
                    }
                    NetworkInfo(
                        connectionType = connectionType,
                        isConnected = true,
                        networkOperator = telephonyManager?.networkOperatorName ?: "Unknown",
                        isRoaming = telephonyManager?.isNetworkRoaming ?: false,
                        signalStrength = getSignalStrength()
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                if (networkInfo == null || !networkInfo.isConnected) {
                    NetworkInfo(
                        connectionType = "NONE",
                        isConnected = false,
                        networkOperator = telephonyManager?.networkOperatorName ?: "Unknown",
                        isRoaming = telephonyManager?.isNetworkRoaming ?: false,
                        signalStrength = -1
                    )
                } else {
                    val type = when (networkInfo.type) {
                        ConnectivityManager.TYPE_WIFI -> "WIFI"
                        ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                        ConnectivityManager.TYPE_ETHERNET -> "ETHERNET"
                        ConnectivityManager.TYPE_VPN -> "VPN"
                        else -> "OTHER"
                    }
                    NetworkInfo(
                        connectionType = type,
                        isConnected = true,
                        networkOperator = telephonyManager?.networkOperatorName ?: "Unknown",
                        isRoaming = telephonyManager?.isNetworkRoaming ?: false,
                        signalStrength = getSignalStrength()
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfo", "Error getting network info", e)
            NetworkInfo(
                connectionType = "UNKNOWN",
                isConnected = false,
                networkOperator = "Unknown",
                isRoaming = false,
                signalStrength = -1
            )
        }
    }

    fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val nativeHeapSize = Debug.getNativeHeapSize() / 1024
        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize() / 1024

        return MemoryInfo(
            heapSizeKB = runtime.totalMemory() / 1024,
            heapAllocatedKB = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            heapFreeKB = runtime.freeMemory() / 1024,
            nativeHeapSizeKB = nativeHeapSize,
            nativeHeapAllocatedKB = nativeHeapAllocated,
            memoryClass = activityManager.memoryClass,
            largeMemoryClass = activityManager.largeMemoryClass
        )
    }

    fun getCpuInfo(): CpuInfo {
        val coreCount = Runtime.getRuntime().availableProcessors()
        val architecture = System.getProperty("os.arch") ?: "unknown"

        return CpuInfo(
            coreCount = coreCount,
            architecture = architecture,
            cpuUsagePercent = getCpuUsage()
        )
    }

    fun getProcessInfo(): ProcessInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processId = android.os.Process.myPid()

        val runningProcesses = activityManager.runningAppProcesses
        val currentProcess = runningProcesses?.find { it.pid == processId }

        return ProcessInfo(
            processId = processId,
            processName = currentProcess?.processName ?: context.packageName,
            importance = when (currentProcess?.importance) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "BACKGROUND"
                else -> "UNKNOWN"
            },
            foreground = currentProcess?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        )
    }

    // ==================== NEW METHODS FOR MISSING FIELDS ====================

    /**
     * Check if device is in power save mode (battery saver)
     */
    fun isPowerSaveMode(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                powerManager.isPowerSaveMode
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if developer mode is enabled
     */
    fun isDeveloperMode(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Settings.Global.getInt(
                    context.contentResolver,
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    0
                ) != 0
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if this is a debug build
     */
    fun isDebugBuild(): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get device boot time (milliseconds since epoch)
     */
    fun getBootTime(): Long {
        return try {
            System.currentTimeMillis() - SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get device uptime (milliseconds since boot)
     */
    fun getDeviceUptime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get device timezone
     */
    fun getTimezone(): String {
        return try {
            TimeZone.getDefault().id
        } catch (e: Exception) {
            "Unknown"
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private fun getBatteryLevel(): Float {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

            if (level >= 0 && scale > 0) {
                level.toFloat() / scale.toFloat()
            } else {
                -1f
            }
        } catch (e: Exception) {
            -1f
        }
    }

    private fun isCharging(): Boolean {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    private fun getBatteryTemperature(): Float {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            temperature / 10.0f
        } catch (e: Exception) {
            -1f
        }
    }

    private fun getAvailableMemoryMB(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = AndroidMemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getTotalMemoryMB(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = AndroidMemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun isLowMemory(): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = AndroidMemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.lowMemory
        } catch (e: Exception) {
            false
        }
    }

    private fun getAvailableStorageMB(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getTotalStorageMB(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            totalBytes / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getSignalStrength(): Int {
        return -1  // Requires READ_PHONE_STATE permission
    }

    private fun getCpuUsage(): Float {
        return try {
            val stat = java.io.File("/proc/stat").readText()
            val lines = stat.split("\n")
            val cpuLine = lines.firstOrNull { it.startsWith("cpu ") }
            if (cpuLine != null) {
                val values = cpuLine.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
                if (values.size >= 4) {
                    val total = values.sum()
                    val idle = values[3]
                    ((total - idle).toFloat() / total.toFloat()) * 100
                } else {
                    -1f
                }
            } else {
                -1f
            }
        } catch (e: Exception) {
            -1f
        }
    }

    // ==================== NEW METHODS FOR ADDITIONAL MISSING FIELDS ====================

    /**
     * Check if device is using VPN
     */
    fun isVPNActive(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if device is using proxy
     */
    fun isProxyActive(): Boolean {
        return try {
            val host = System.getProperty("http.proxyHost")
            val port = System.getProperty("http.proxyPort")
            !host.isNullOrEmpty() && !port.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get external SD card info
     */
    fun getExternalSDCardInfo(): SDCardInfo {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val externalDirs = context.getExternalFilesDirs(null)
                val hasSDCard = externalDirs.size > 1

                if (hasSDCard && externalDirs[1] != null) {
                    val sdCardPath = externalDirs[1].absolutePath
                    val stat = StatFs(sdCardPath)
                    val totalBytes = stat.blockCountLong * stat.blockSizeLong
                    val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

                    SDCardInfo(
                        hasSDCard = true,
                        path = sdCardPath,
                        totalMB = totalBytes / (1024 * 1024),
                        availableMB = availableBytes / (1024 * 1024)
                    )
                } else {
                    SDCardInfo(hasSDCard = false, path = "", totalMB = 0, availableMB = 0)
                }
            } else {
                SDCardInfo(hasSDCard = false, path = "", totalMB = 0, availableMB = 0)
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfo", "Error getting SD card info", e)
            SDCardInfo(hasSDCard = false, path = "", totalMB = 0, availableMB = 0)
        }
    }

    /**
     * Get disk I/O performance metrics
     */
    fun getDiskPerformance(): DiskPerformance {
        return try {
            val startTime = System.nanoTime()

            // Test write performance
            val testFile = java.io.File(context.cacheDir, "disk_test.tmp")
            val testData = ByteArray(1024 * 1024) // 1 MB
            testFile.outputStream().use { it.write(testData) }

            val writeTime = System.nanoTime()
            val writeDuration = (writeTime - startTime) / 1_000_000.0 // Convert to ms
            val writeSpeed = if (writeDuration > 0) (1024 / writeDuration) else 0.0 // MB/s

            // Test read performance
            testFile.inputStream().use { it.readBytes() }
            val readTime = System.nanoTime()
            val readDuration = (readTime - writeTime) / 1_000_000.0 // Convert to ms
            val readSpeed = if (readDuration > 0) (1024 / readDuration) else 0.0 // MB/s

            // Cleanup
            testFile.delete()

            DiskPerformance(
                readSpeedMBps = readSpeed,
                writeSpeedMBps = writeSpeed,
                testTimestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfo", "Error measuring disk performance", e)
            DiskPerformance(readSpeedMBps = 0.0, writeSpeedMBps = 0.0, testTimestamp = System.currentTimeMillis())
        }
    }

    /**
     * Get memory pressure level
     */
    fun getMemoryPressure(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = AndroidMemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val availablePercent = (memoryInfo.availMem.toFloat() / memoryInfo.totalMem.toFloat()) * 100

            when {
                availablePercent < 10 -> "CRITICAL"
                availablePercent < 20 -> "HIGH"
                availablePercent < 40 -> "MODERATE"
                else -> "LOW"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}
