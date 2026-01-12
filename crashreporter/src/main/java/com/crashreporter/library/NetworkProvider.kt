package com.crashreporter.library

/**
 * Abstraction for network operations
 * Allows different implementations (OkHttp, Retrofit, Mock for testing)
 */
interface NetworkProvider {
    suspend fun post(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): NetworkResult
}

sealed class NetworkResult {
    data class Success(val responseCode: Int, val body: String) : NetworkResult()
    data class Failure(val error: String, val responseCode: Int = 0) : NetworkResult()
}
