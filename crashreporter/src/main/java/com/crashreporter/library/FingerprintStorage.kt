package com.crashreporter.library

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Persistent Fingerprint Storage
 *
 * Stores reported crash fingerprints to disk so they survive app crashes.
 * Prevents duplicate crash reports even after app restart.
 *
 * Features:
 * - Persists fingerprints to disk (survives crashes)
 * - Tracks timestamp for each fingerprint
 * - Automatic cleanup of old fingerprints (7+ days)
 * - Thread-safe operations
 */
class FingerprintStorage(private val context: Context) {

    private val file = File(context.cacheDir, "crash_fingerprints.json")
    private val countsFile = File(context.cacheDir, "crash_fingerprint_counts.json")
    private val gson: Gson = GsonBuilder().create()
    private val fingerprints = mutableMapOf<String, Long>()  // fingerprint → timestamp
    private val counts = mutableMapOf<String, Int>()          // fingerprint → occurrences in window
    private val lock = Any()

    init {
        load()
        loadCounts()
    }

    /** Increment and return the occurrence count for a fingerprint (survives restarts). */
    fun incrementCount(fingerprint: String): Int {
        if (fingerprint.isEmpty()) return 1
        synchronized(lock) {
            val next = (counts[fingerprint] ?: 0) + 1
            counts[fingerprint] = next
            saveCounts()
            return next
        }
    }

    /** Current occurrence count for a fingerprint (0 if unseen). */
    fun getOccurrenceCount(fingerprint: String): Int {
        synchronized(lock) { return counts[fingerprint] ?: 0 }
    }

    /**
     * Check if this crash fingerprint was recently reported
     * Returns true if seen within the last 7 days
     */
    fun wasRecentlyReported(fingerprint: String): Boolean {
        synchronized(lock) {
            val lastReportedTime = fingerprints[fingerprint] ?: return false
            val now = System.currentTimeMillis()
            val ageMs = now - lastReportedTime
            val oneDayMs = 24 * 60 * 60 * 1000L

            val wasRecent = ageMs < oneDayMs
            if (wasRecent) {
                android.util.Log.d(
                    "FingerprintStorage",
                    "✅ Duplicate detected: $fingerprint (${ageMs / 1000}s ago)"
                )
            }
            return wasRecent
        }
    }

    /**
     * Mark a crash fingerprint as reported
     * Saves to disk immediately
     */
    fun markAsReported(fingerprint: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            fingerprints[fingerprint] = now
            android.util.Log.d("FingerprintStorage", "📌 Marked as reported: $fingerprint")
            save()  // Persist to disk immediately
        }
    }

    /**
     * Get all stored fingerprints
     */
    fun getAllFingerprints(): Map<String, Long> {
        synchronized(lock) {
            return fingerprints.toMap()
        }
    }

    /**
     * Get count of stored fingerprints
     */
    fun getCount(): Int {
        synchronized(lock) {
            return fingerprints.size
        }
    }

    /**
     * Clear all stored fingerprints
     */
    fun clear() {
        synchronized(lock) {
            fingerprints.clear()
            android.util.Log.d("FingerprintStorage", "🗑️ Cleared all fingerprints")
            save()
        }
    }

    /**
     * Load fingerprints from disk
     * Called on initialization
     */
    private fun load() {
        synchronized(lock) {
            try {
                if (!file.exists()) {
                    android.util.Log.d("FingerprintStorage", "No fingerprint file yet")
                    return
                }

                val json = file.readText()
                @Suppress("UNCHECKED_CAST")
                val loaded = gson.fromJson(json, Map::class.java) as? Map<String, Double>

                if (loaded != null) {
                    fingerprints.clear()
                    loaded.forEach { (fingerprint, timestamp) ->
                        fingerprints[fingerprint] = timestamp.toLong()
                    }
                    android.util.Log.d(
                        "FingerprintStorage",
                        "✅ Loaded ${fingerprints.size} fingerprints from disk"
                    )
                }

                // Clean up old fingerprints while we're at it
                cleanup()
            } catch (e: Exception) {
                android.util.Log.w(
                    "FingerprintStorage",
                    "Failed to load fingerprints: ${e.message}"
                )
                fingerprints.clear()
            }
        }
    }

    private fun loadCounts() {
        synchronized(lock) {
            try {
                if (!countsFile.exists()) return
                @Suppress("UNCHECKED_CAST")
                val loaded = gson.fromJson(countsFile.readText(), Map::class.java) as? Map<String, Double>
                if (loaded != null) {
                    counts.clear()
                    loaded.forEach { (fp, c) -> counts[fp] = c.toInt() }
                }
            } catch (e: Exception) {
                android.util.Log.w("FingerprintStorage", "Failed to load counts: ${e.message}")
                counts.clear()
            }
        }
    }

    private fun saveCounts() {
        synchronized(lock) {
            try {
                countsFile.writeText(gson.toJson(counts))
            } catch (e: Exception) {
                android.util.Log.e("FingerprintStorage", "Failed to save counts: ${e.message}")
            }
        }
    }

    /**
     * Save fingerprints to disk
     */
    private fun save() {
        synchronized(lock) {
            try {
                val json = gson.toJson(fingerprints)
                file.writeText(json)
                android.util.Log.d(
                    "FingerprintStorage",
                    "💾 Saved ${fingerprints.size} fingerprints to disk"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "FingerprintStorage",
                    "Failed to save fingerprints: ${e.message}"
                )
            }
        }
    }

    /**
     * Remove fingerprints older than 24 hours
     * Called automatically during load and periodically
     */
    private fun cleanup() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val oneDayMs = 24 * 60 * 60 * 1000L
            val oneDayAgo = now - oneDayMs

            val before = fingerprints.size
            fingerprints.entries.removeAll { (_, timestamp) ->
                timestamp < oneDayAgo
            }
            val after = fingerprints.size
            val removed = before - after

            // Drop occurrence counts for fingerprints that aged out of the window too.
            val countsBefore = counts.size
            counts.keys.retainAll(fingerprints.keys)
            if (counts.size != countsBefore) saveCounts()

            if (removed > 0) {
                android.util.Log.d(
                    "FingerprintStorage",
                    "🧹 Cleaned up $removed old fingerprints"
                )
                save()  // Save the cleaned-up list
            }
        }
    }

    /**
     * Periodic cleanup - call this from your crash reporter
     * every hour or on app foreground
     */
    fun performPeriodicCleanup() {
        synchronized(lock) {
            cleanup()
        }
    }
}
