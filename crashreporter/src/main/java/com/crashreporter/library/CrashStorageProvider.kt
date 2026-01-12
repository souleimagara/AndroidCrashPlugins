package com.crashreporter.library

import java.io.File

/**
 * Abstraction for crash storage
 * Allows different implementations (filesystem, cloud, in-memory for testing)
 */
interface CrashStorageProvider {
    suspend fun saveCrash(crashData: CrashData): Boolean
    suspend fun loadCrash(crashId: String): CrashData?
    suspend fun deleteCrash(crashId: String): Boolean
    suspend fun markAsSent(crashId: String): Boolean
    fun getPendingCrashFiles(): List<File>
    fun getCrashFile(crashId: String): File?
    fun getPendingCrashCount(): Int
    suspend fun deleteAllCrashes()
}
