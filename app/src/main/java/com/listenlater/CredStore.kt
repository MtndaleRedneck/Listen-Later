package com.listenlater

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Session state in Keystore-backed encrypted preferences. Holds the login token,
 * never the password. The cached feed link contains an API key, so it lives here
 * too and is never logged or shown.
 */
class CredStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var server: String?
        get() = prefs.getString("server", null)
        set(v) = prefs.edit().putString("server", v).apply()

    var username: String?
        get() = prefs.getString("username", null)
        set(v) = prefs.edit().putString("username", v).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    var feedUrl: String?
        get() = prefs.getString("feedUrl", null)
        set(v) = prefs.edit().putString("feedUrl", v).apply()

    var loginAtMs: Long
        get() = prefs.getLong("loginAt", 0L)
        set(v) = prefs.edit().putLong("loginAt", v).apply()

    var needsLogin: Boolean
        get() = prefs.getBoolean("needsLogin", false)
        set(v) = prefs.edit().putBoolean("needsLogin", v).apply()

    var loginNotified: Boolean
        get() = prefs.getBoolean("loginNotified", false)
        set(v) = prefs.edit().putBoolean("loginNotified", v).apply()

    /** A version the user chose to skip; not offered again. */
    var skippedUpdate: String?
        get() = prefs.getString("skipped_update", null)
        set(v) = prefs.edit().putString("skipped_update", v).apply()

    var attempts: Int
        get() = prefs.getInt("attempts", 0)
        set(v) = prefs.edit().putInt("attempts", v).apply()

    fun markLoggedIn(server: String, username: String, token: String) {
        prefs.edit()
            .putString("server", server)
            .putString("username", username)
            .putString("token", token)
            .putLong("loginAt", System.currentTimeMillis())
            .putBoolean("needsLogin", false)
            .putBoolean("loginNotified", false)
            .putInt("attempts", 0)
            .apply()
    }

    fun shareQueue(): List<String> {
        val raw = prefs.getString("queue", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun saveQueue(list: List<String>) {
        prefs.edit().putString("queue", JSONArray(list).toString()).apply()
    }

    fun enqueueShare(url: String) {
        val q = shareQueue().toMutableList()
        if (!q.contains(url)) {
            q.add(url)
            saveQueue(q)
        }
    }

    fun removeFromQueue(url: String) {
        val q = shareQueue().toMutableList()
        if (q.remove(url)) saveQueue(q)
    }

    fun clear() = prefs.edit().clear().apply()
}
