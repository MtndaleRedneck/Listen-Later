package com.listenlater

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Session state in Keystore-backed encrypted preferences. Holds the login token
 * and, while Remember me is on, the saved password. The cached feed link contains
 * an API key, so it lives here too and is never logged or shown.
 *
 * One shared instance for the whole process: building the key and the encrypted
 * file per share, per worker run and per screen was slow and raced the Keystore.
 * Recovery is retry-first, delete-last: a first failure is usually a transient
 * Keystore hiccup, so it waits a beat and tries once more; only a second failure
 * (the data is unreadable anyway) deletes and starts fresh; if even that fails,
 * the app runs signed out in memory and asks him to sign in - it never crash-loops.
 *
 * The share queue, retry counter and skipped-update choice are not secrets, so
 * they live in [QueueStore] (plain preferences) - a storage wipe then costs a
 * sign-in, never the queue.
 */
class CredStore private constructor(private val appContext: Context, private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.applicationContext, shared(context.applicationContext))

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

    /** On by default; the user's last choice sticks. Turning it off wipes the saved password. */
    var rememberMe: Boolean
        get() = prefs.getBoolean("rememberMe", true)
        set(v) {
            if (v) prefs.edit().putBoolean("rememberMe", true).apply()
            else prefs.edit().putBoolean("rememberMe", false).remove("password").apply()
        }

    /** Only populated while rememberMe is on; never log this. */
    var savedPassword: String?
        get() = prefs.getString("password", null)
        private set(v) = prefs.edit().putString("password", v).apply()

    fun markLoggedIn(server: String, username: String, token: String, password: String, remember: Boolean) {
        val e = prefs.edit()
        // A different server or username means a different account: the previous account's
        // feed link (it carries an API key) and any queued shares must not carry over.
        // Only wipe when a previous account actually exists: before the first sign-in the
        // store is empty and the queue may hold pre-login shares waiting to be sent.
        val prevServer = this.server
        if (prevServer != null && (prevServer != server || this.username != username)) {
            e.remove("feedUrl")
            QueueStore(appContext).clearAll()
        }
        e.putString("server", server)
            .putString("username", username)
            .putString("token", token)
            .putLong("loginAt", System.currentTimeMillis())
            .putBoolean("needsLogin", false)
            .putBoolean("loginNotified", false)
            .putBoolean("rememberMe", remember)
        if (remember) e.putString("password", password) else e.remove("password")
        e.apply()
    }

    /**
     * Sign out ends the session only. Server, username, and the saved password
     * survive while Remember me is on; unchecking Remember me is what wipes the
     * password. Pending shares, the feed link, and the skipped-update choice are
     * account state and stay too.
     */
    fun signOut() {
        prefs.edit()
            .remove("token")
            .remove("needsLogin")
            .remove("loginNotified")
            .remove("loginAt")
            .apply()
    }

    companion object {
        @Volatile private var instance: SharedPreferences? = null

        private fun shared(ctx: Context): SharedPreferences =
            instance ?: synchronized(this) { instance ?: open(ctx).also { instance = it } }

        private fun create(ctx: Context): SharedPreferences = EncryptedSharedPreferences.create(
            ctx,
            "creds",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        private fun open(ctx: Context): SharedPreferences {
            // A first failure is usually a transient Keystore hiccup: wait a beat, try once more.
            try {
                return create(ctx)
            } catch (e: Exception) {
                // fall through to the retry
            }
            try {
                Thread.sleep(300)
                return create(ctx)
            } catch (e: Exception) {
                // fall through to the fresh start
            }
            // A second failure means the stored data is unreadable anyway; starting fresh loses nothing more.
            try {
                ctx.deleteSharedPreferences("creds")
                return create(ctx)
            } catch (e: Exception) {
                // The Keystore itself is down. Run signed out in memory, ask him to sign
                // in, and never crash. Lasts until the process restarts and tries again.
                val mem = CredStore(ctx, MemoryPrefs())
                Notifier.notifySignInOnce(ctx, mem)
                return mem.prefs
            }
        }

        /**
         * One-time move of the share queue, retry counter and skipped-update choice out of
         * the encrypted store into [QueueStore]. Runs on first launch after the update.
         */
        fun migrateToPlain(ctx: Context) {
            val app = ctx.applicationContext
            val plain = QueueStore(app)
            if (plain.migrated) return
            val enc = shared(app)
            val queue = enc.getString("queue", null)
            val attempts = enc.getInt("attempts", 0)
            val skippedUpdate = enc.getString("skipped_update", null)
            if (queue != null) plain.importQueue(queue)
            if (attempts != 0) plain.attempts = attempts
            if (skippedUpdate != null) plain.skippedUpdate = skippedUpdate
            enc.edit().remove("queue").remove("attempts").remove("skipped_update").apply()
            plain.migrated = true
        }
    }
}

/**
 * Last-resort preferences when the Keystore is down: everything reads as empty,
 * writes live only until the process ends. The app behaves signed out and never crashes.
 */
private class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): Map<String, Any?> = map
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class MemEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { map[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { map[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { map[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { map[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { map.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
