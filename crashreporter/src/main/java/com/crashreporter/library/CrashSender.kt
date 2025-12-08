package com.crashreporter.library

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends crash reports to the server via HTTP
 */
class CrashSender(
    private val apiEndpoint: String,
    private val crashStorage: CrashStorage
) {

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send a single crash report to the server
     */
    suspend fun sendCrash(crashData: CrashData): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(crashData)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toRequestBody(mediaType)

            val url = if (apiEndpoint.endsWith("/")) {
                "${apiEndpoint}api/crashes"
            } else {
                "$apiEndpoint/api/crashes"
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "CrashReporter-Android/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    android.util.Log.i("CrashSender", "Crash sent successfully: ${crashData.crashId}")
                    true
                } else {
                    android.util.Log.w(
                        "CrashSender",
                        "Failed to send crash: ${crashData.crashId}, Status: ${response.code}"
                    )
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CrashSender", "Error sending crash: ${crashData.crashId}", e)
            false
        }
    }

    /**
     * Send all pending crashes from previous sessions
     */
    suspend fun sendAllPendingCrashes() = withContext(Dispatchers.IO) {
        try {
            val pendingFiles = crashStorage.getPendingCrashFiles()

            if (pendingFiles.isEmpty()) {
                android.util.Log.i("CrashSender", "No pending crashes to send")
                return@withContext
            }

            android.util.Log.i("CrashSender", "Sending ${pendingFiles.size} pending crash(es)")

            pendingFiles.forEach { file ->
                try {
                    val crashData = crashStorage.loadCrash(file)

                    if (crashData != null) {
                        val success = sendCrash(crashData)

                        if (success) {
                            // Delete the file after successful upload
                            crashStorage.deleteCrash(file)
                        }
                    } else {
                        android.util.Log.w("CrashSender", "Failed to load crash from ${file.name}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CrashSender", "Error processing crash file ${file.name}", e)
                }
            }

            android.util.Log.i("CrashSender", "Finished sending pending crashes")

        } catch (e: Exception) {
            android.util.Log.e("CrashSender", "Error in sendAllPendingCrashes", e)
        }
    }
}