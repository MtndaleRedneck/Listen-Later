package com.listenlater

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Share queue, retry counter, skipped-update choice, and the "Couldn't get it" list.
 *
 * Plain preferences on purpose: these are YouTube links and app state, not secrets -
 * the API key only ever lives in the encrypted store. If encrypted storage ever has to
 * start fresh, this survives and a wipe costs only a sign-in, never the queue.
 *
 * Every read-modify-write runs under one lock so a share landing at the exact moment
 * the worker removes an item cannot be lost, and whole worker runs are serialized by
 * [QueueLock] in ShareWorker.
 */
class QueueStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("queue_store", Context.MODE_PRIVATE)

    /** A video the server refused, kept so he can Try again. Title may be unknown. */
    data class Skipped(val url: String, val title: String, val reason: String)

    var attempts: Int
        get() = prefs.getInt("attempts", 0)
        set(v) = prefs.edit().putInt("attempts", v).apply()

    /** A version the user chose to skip; not offered again. */
    var skippedUpdate: String?
        get() = prefs.getString("skipped_update", null)
        set(v) = prefs.edit().putString("skipped_update", v).apply()

    /** Set once the one-time move out of the old encrypted store has run. */
    var migrated: Boolean
        get() = prefs.getBoolean("migrated", false)
        set(v) = prefs.edit().putBoolean("migrated", v).apply()

    fun shareQueue(): List<String> = synchronized(QueueStore::class.java) {
        val raw = prefs.getString("queue", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveQueue(list: List<String>) {
        prefs.edit().putString("queue", JSONArray(list).toString()).apply()
    }

    fun enqueueShare(url: String): Unit = synchronized(QueueStore::class.java) {
        val q = shareQueue().toMutableList()
        if (!q.contains(url)) {
            q.add(url)
            saveQueue(q)
        }
    }

    fun removeFromQueue(url: String): Unit = synchronized(QueueStore::class.java) {
        val q = shareQueue().toMutableList()
        if (q.remove(url)) saveQueue(q)
    }

    /** Used by the one-time migration; takes the old encrypted store's raw JSON. */
    fun importQueue(rawJson: String): Unit = synchronized(QueueStore::class.java) {
        try {
            JSONArray(rawJson) // validate before trusting it
            prefs.edit().putString("queue", rawJson).apply()
        } catch (e: Exception) {
            // Corrupt legacy value: leave the queue empty rather than crash a first launch.
        }
    }

    fun skipped(): List<Skipped> = synchronized(QueueStore::class.java) {
        val raw = prefs.getString("skipped", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Skipped(o.optString("u", ""), o.optString("t", ""), o.optString("r", ""))
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSkipped(list: List<Skipped>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("r", it.reason)) }
        prefs.edit().putString("skipped", arr.toString()).apply()
    }

    fun addSkipped(url: String, title: String, reason: String): Unit = synchronized(QueueStore::class.java) {
        val list = skipped().filter { it.url != url }.toMutableList()
        list.add(Skipped(url, title, reason))
        saveSkipped(list)
    }

    fun removeSkipped(url: String): Unit = synchronized(QueueStore::class.java) {
        val list = skipped().filter { it.url != url }
        if (list.size != skipped().size) saveSkipped(list)
    }

    /** Account switch: nothing from the previous account carries over. */
    fun clearAll(): Unit = synchronized(QueueStore::class.java) {
        prefs.edit().remove("queue").remove("attempts").remove("skipped").apply()
    }

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
