package com.crashreporter.library

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles saving and loading crash reports from disk
 */
class CrashStorage(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private val crashDir: File by lazy {
        File(context.filesDir, "crashes").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Save a crash report to disk as JSON
     */
    suspend fun saveCrash(crashData: CrashData) = withContext(Dispatchers.IO) {
        try {
            val fileName = "crash_${crashData.crashId}.json"
            val file = File(crashDir, fileName)

            val json = gson.toJson(crashData)
            file.writeText(json)

            android.util.Log.i("CrashStorage", "Crash saved: $fileName")
        } catch (e: Exception) {
            android.util.Log.e("CrashStorage", "Error saving crash", e)
        }
    }

    /**
     * Get all pending crash files
     */
    fun getPendingCrashFiles(): List<File> {
        return try {
            crashDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("CrashStorage", "Error getting pending crashes", e)
            emptyList()
        }
    }

    /**
     * Load a crash report from a file
     */
    suspend fun loadCrash(file: File): CrashData? = withContext(Dispatchers.IO) {
        try {
            val json = file.readText()
            gson.fromJson(json, CrashData::class.java)
        } catch (e: Exception) {
            android.util.Log.e("CrashStorage", "Error loading crash from ${file.name}", e)
            null
        }
    }

    /**
     * Delete a crash file after successful upload
     */

    /**
     * Delete a crash file after successful upload
     */
    suspend fun deleteCrash(file: File) {
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    file.delete()
                    android.util.Log.i("CrashStorage", "Crash deleted: ${file.name}")
                } else {
                    // Do nothing, but the 'else' branch is now present
                }
            } catch (e: Exception) {
                android.util.Log.e("CrashStorage", "Error deleting crash ${file.name}", e)
            }
        }
    }


    /**
     * Get the total number of pending crashes
     */
    fun getPendingCrashCount(): Int {
        return getPendingCrashFiles().size
    }

    /**
     * Delete all crash files (use with caution)
     */
    suspend fun deleteAllCrashes() = withContext(Dispatchers.IO) {
        try {
            getPendingCrashFiles().forEach { it.delete() }
            android.util.Log.i("CrashStorage", "All crashes deleted")
        } catch (e: Exception) {
            android.util.Log.e("CrashStorage", "Error deleting all crashes", e)
        }
    }
}