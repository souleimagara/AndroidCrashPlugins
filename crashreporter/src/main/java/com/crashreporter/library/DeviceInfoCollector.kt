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
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale
import android.app.ActivityManager.MemoryInfo as AndroidMemoryInfo

/**
 * Collects comprehensive device, app, and system state information
 */
class DeviceInfoCollector(private val context: Context) {

    /**
     * Get enhanced device hardware information
     */
    fun getDeviceInfo(): DeviceInfo {
        // ✅ Safe for applicationContext: just use resources.displayMetrics
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


    /**
     * Get enhanced application information
     */
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
            android.util.Log.e("DeviceInfoCollector", "Error getting app info", e)
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

    /**
     * Get enhanced device state (battery, memory, storage, screen)
     */
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

    /**
     * Get enhanced network connectivity information
     */
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
            android.util.Log.e("DeviceInfoCollector", "Error getting network info", e)
            NetworkInfo(
                connectionType = "UNKNOWN",
                isConnected = false,
                networkOperator = "Unknown",
                isRoaming = false,
                signalStrength = -1
            )
        }
    }

    /**
     * Get detailed memory information (heap, native heap)
     */
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

    /**
     * Get CPU information
     */
    fun getCpuInfo(): CpuInfo {
        val coreCount = Runtime.getRuntime().availableProcessors()
        val architecture = System.getProperty("os.arch") ?: "unknown"

        return CpuInfo(
            coreCount = coreCount,
            architecture = architecture,
            cpuUsagePercent = getCpuUsage()
        )
    }

    /**
     * Get process information
     */
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
            android.util.Log.e("DeviceInfoCollector", "Error getting battery level", e)
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
            android.util.Log.e("DeviceInfoCollector", "Error checking charging status", e)
            false
        }
    }

    private fun getBatteryTemperature(): Float {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            temperature / 10.0f // Temperature is in tenths of degrees Celsius
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfoCollector", "Error getting battery temperature", e)
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
            android.util.Log.e("DeviceInfoCollector", "Error getting available memory", e)
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
            android.util.Log.e("DeviceInfoCollector", "Error getting total memory", e)
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
            android.util.Log.e("DeviceInfoCollector", "Error checking low memory", e)
            false
        }
    }

    private fun getAvailableStorageMB(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfoCollector", "Error getting available storage", e)
            0L
        }
    }

    private fun getTotalStorageMB(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            totalBytes / (1024 * 1024)
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfoCollector", "Error getting total storage", e)
            0L
        }
    }

    private fun getSignalStrength(): Int {
        // Returning -1 as getting signal strength requires READ_PHONE_STATE permission
        // which is a dangerous permission and requires user approval
        return -1
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
            android.util.Log.e("DeviceInfoCollector", "Error getting CPU usage", e)
            -1f
        }
    }
}