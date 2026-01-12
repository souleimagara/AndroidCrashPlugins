package com.crashreporter.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of NetworkProvider
 */
class OkHttpNetworkProvider : NetworkProvider {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun post(url: String, jsonBody: String, headers: Map<String, String>): NetworkResult = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)

            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)

            // Add headers
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val request = requestBuilder.build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    NetworkResult.Success(response.code, response.body?.string() ?: "")
                } else {
                    NetworkResult.Failure(
                        "HTTP ${response.code}: ${response.message}",
                        response.code
                    )
                }
            }
        } catch (e: Exception) {
            NetworkResult.Failure("Network error: ${e.message}", 0)
        }
    }
}
