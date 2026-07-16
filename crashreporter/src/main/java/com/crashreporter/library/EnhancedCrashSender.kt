package com.crashreporter.library

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Builds the flattened crash payload the Unity host pulls, signs and sends.
 *
 * The SDK runs host-driven (deferSendToHost): native only stores crashes and hands them to Unity
 * C# via EnhancedCrashReporter.getPendingCrashesAsJson(); Unity signs and POSTs them. So this class
 * only FORMATS the payload — it never talks to the network.
 */
class EnhancedCrashSender {

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    /**
     * Build the flattened crash payload as a single JSON object. Nested objects (deviceInfo,
     * appInfo, deviceState, etc.) are flattened to top-level primitives and arrays removed.
     * Pass an already-optimized CrashData.
     */
    fun buildPayloadObject(crashData: CrashData): com.google.gson.JsonObject {
        val crashJsonObject = gson.toJsonTree(crashData).asJsonObject
        crashJsonObject.addProperty("eventType", "ZBDCrashReport")
        crashJsonObject.addProperty("gameId", crashData.appInfo.packageName)
        // Schema version so the backend can branch parsing as the payload contract evolves.
        crashJsonObject.addProperty("payloadSchemaVersion", 1)

        // Flatten deviceInfo
        crashJsonObject.addProperty("deviceModel", crashData.deviceInfo.model)
        crashJsonObject.addProperty("deviceManufacturer", crashData.deviceInfo.manufacturer)
        crashJsonObject.addProperty("androidVersion", crashData.deviceInfo.androidVersion)
        crashJsonObject.addProperty("deviceApiLevel", crashData.deviceInfo.apiLevel)
        // Cross-platform, consistently-typed OS fields (osApiLevel always numeric, osVersion always
        // string) so the SLO dashboard can group iOS+Android reliably. Additive — leaves the legacy
        // deviceApiLevel field untouched so existing queries keep working.
        crashJsonObject.addProperty("osApiLevel", crashData.deviceInfo.apiLevel)
        crashJsonObject.addProperty("osVersion", "Android ${crashData.deviceInfo.androidVersion}")
        crashJsonObject.addProperty("deviceBrand", crashData.deviceInfo.brand)
        crashJsonObject.addProperty("screenWidth", crashData.deviceInfo.screenWidth)
        crashJsonObject.addProperty("screenHeight", crashData.deviceInfo.screenHeight)
        crashJsonObject.remove("deviceInfo")

        // Flatten appInfo
        crashJsonObject.addProperty("appVersion", crashData.appInfo.versionName)
        crashJsonObject.addProperty("appPackageName", crashData.appInfo.packageName)
        crashJsonObject.addProperty("appVersionCode", crashData.appInfo.versionCode)
        crashJsonObject.remove("appInfo")

        // Flatten deviceState
        crashJsonObject.addProperty("batteryLevel", crashData.deviceState.batteryLevel)
        crashJsonObject.addProperty("isCharging", crashData.deviceState.isCharging)
        crashJsonObject.addProperty("availableMemoryMB", crashData.deviceState.availableMemoryMB)
        crashJsonObject.addProperty("totalMemoryMB", crashData.deviceState.totalMemoryMB)
        crashJsonObject.addProperty("lowMemory", crashData.deviceState.lowMemory)
        crashJsonObject.addProperty("orientation", crashData.deviceState.orientation)
        crashJsonObject.remove("deviceState")

        // Flatten networkInfo
        crashJsonObject.addProperty("networkConnected", crashData.networkInfo.isConnected)
        crashJsonObject.addProperty("networkType", crashData.networkInfo.connectionType)
        crashJsonObject.remove("networkInfo")

        // Flatten memoryInfo
        crashJsonObject.addProperty("heapSizeKB", crashData.memoryInfo.heapSizeKB)
        crashJsonObject.addProperty("heapFreeKB", crashData.memoryInfo.heapFreeKB)
        crashJsonObject.remove("memoryInfo")

        // Flatten cpuInfo
        crashJsonObject.addProperty("cpuCores", crashData.cpuInfo.coreCount)
        crashJsonObject.addProperty("cpuArchitecture", crashData.cpuInfo.architecture)
        crashJsonObject.remove("cpuInfo")

        // Flatten processInfo
        crashJsonObject.addProperty("processName", crashData.processInfo.processName)
        crashJsonObject.addProperty("processForeground", crashData.processInfo.foreground)
        crashJsonObject.remove("processInfo")

        // Flatten key operation fields from customData to top level
        crashData.customData["currentOperation"]?.let { crashJsonObject.addProperty("currentOperation", it) }
        crashData.customData["lastSuccessfulOperation"]?.let { crashJsonObject.addProperty("lastSuccessfulOperation", it) }
        crashData.customData["lastFailedOperation"]?.let { crashJsonObject.addProperty("lastFailedOperation", it) }
        crashData.customData["lastOperationError"]?.let { crashJsonObject.addProperty("lastOperationError", it) }

        // Remove arrays and nested objects the backend cannot store flat
        crashJsonObject.remove("allThreads")
        crashJsonObject.remove("breadcrumbs")
        crashJsonObject.remove("memoryWarnings")
        crashJsonObject.remove("networkChanges")
        crashJsonObject.remove("nativeRegisters")
        crashJsonObject.remove("operationContext")
        crashJsonObject.remove("sdCardInfo")
        crashJsonObject.remove("diskPerformance")
        crashJsonObject.remove("sessionInfo")
        crashJsonObject.remove("memoryState")
        crashJsonObject.remove("customData")

        return crashJsonObject
    }

    /** Single-crash payload as a JSON string — exactly what the host signs and sends. */
    fun buildPayloadJson(crashData: CrashData): String = gson.toJson(buildPayloadObject(crashData))
}
