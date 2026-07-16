package com.crashreporter.library

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Filesystem implementation of CrashStorageProvider
 * FIXED: Prevents duplicate sends by marking crashes as sent
 */
class FileCrashStorage(private val context: Context) : CrashStorageProvider {

    private companion object {
        // Bound the UNDELIVERED backlog so it can't grow without limit in the host game when
        // delivery is failing/offline. Kept generous + logged (never silent). Parity with iOS.
        const val MAX_PENDING_CRASHES = 100
        const val MAX_PENDING_AGE_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
    }

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

    private val sentDir: File by lazy {
        File(context.filesDir, "crashes_sent").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    override suspend fun saveCrash(crashData: CrashData): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = "crash_${crashData.crashId}.json"
            val file = File(crashDir, fileName)

            val json = gson.toJson(crashData)
            file.writeText(json)

            android.util.Log.i("FileCrashStorage", "✅ Crash saved: $fileName")
            enforcePendingLimits()
            true
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "❌ Error saving crash", e)
            // Fallback: Try to log crash to logcat at least
            try {
                android.util.Log.e("FileCrashStorage", "CRASH LOST: ${gson.toJson(crashData)}")
            } catch (ex: Exception) {
                android.util.Log.e("FileCrashStorage", "Failed to log crash", ex)
            }
            false
        }
    }

    override suspend fun loadCrash(crashId: String): CrashData? = withContext(Dispatchers.IO) {
        try {
            val file = getCrashFile(crashId)
            if (file != null && file.exists()) {
                val json = file.readText()
                gson.fromJson(json, CrashData::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error loading crash: $crashId", e)
            null
        }
    }

    override suspend fun deleteCrash(crashId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getCrashFile(crashId)
            if (file != null && file.exists()) {
                file.delete()
                android.util.Log.i("FileCrashStorage", "🗑️ Crash deleted: $crashId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error deleting crash: $crashId", e)
            false
        }
    }

    override suspend fun markAsSent(crashId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getCrashFile(crashId)
            if (file != null && file.exists()) {
                // Move to sent directory
                val sentFile = File(sentDir, file.name)
                file.renameTo(sentFile)
                android.util.Log.i("FileCrashStorage", "✅ Crash marked as sent: $crashId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error marking crash as sent: $crashId", e)
            false
        }
    }

    override fun getPendingCrashFiles(): List<File> {
        return try {
            // Only return files from crashDir (not sent)
            crashDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error getting pending crashes", e)
            emptyList()
        }
    }

    override fun getCrashFile(crashId: String): File? {
        val fileName = "crash_$crashId.json"
        val file = File(crashDir, fileName)
        return if (file.exists()) file else null
    }

    override fun getPendingCrashCount(): Int {
        return getPendingCrashFiles().size
    }

    override suspend fun deleteAllCrashes(): Unit = withContext(Dispatchers.IO) {
        try {
            getPendingCrashFiles().forEach { it.delete() }
            sentDir.listFiles()?.forEach { it.delete() }
            android.util.Log.i("FileCrashStorage", "🗑️ All crashes deleted")
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error deleting all crashes", e)
        }
    }

    /**
     * Bound the pending (undelivered) crash backlog: drop crashes older than the max age, then, if
     * still over the count cap, drop the OLDEST. Loss is logged (never silent). Runs on the IO
     * dispatcher via its saveCrash caller.
     */
    private fun enforcePendingLimits() {
        try {
            val now = System.currentTimeMillis()
            var droppedAge = 0
            crashDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
                if (now - f.lastModified() > MAX_PENDING_AGE_MS && f.delete()) droppedAge++
            }

            var droppedCount = 0
            val remaining = crashDir.listFiles()?.filter { it.extension == "json" }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            if (remaining.size > MAX_PENDING_CRASHES) {
                remaining.take(remaining.size - MAX_PENDING_CRASHES).forEach { if (it.delete()) droppedCount++ }
            }

            if (droppedAge + droppedCount > 0) {
                android.util.Log.w(
                    "FileCrashStorage",
                    "⚠️ Pending backlog over limit — dropped $droppedAge stale + $droppedCount oldest UNDELIVERED crash(es) (cap=$MAX_PENDING_CRASHES, maxAge=30d)"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error enforcing pending limits", e)
        }
    }

    /**
     * Clean up old sent crashes (older than 7 days)
     */
    suspend fun cleanupOldSentCrashes() = withContext(Dispatchers.IO) {
        try {
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
            sentDir.listFiles()?.forEach { file ->
                if (file.lastModified() < sevenDaysAgo) {
                    file.delete()
                    android.util.Log.d("FileCrashStorage", "Deleted old sent crash: ${file.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileCrashStorage", "Error cleaning up old crashes", e)
        }
    }
}
