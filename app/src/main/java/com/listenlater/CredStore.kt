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
class CredStore private constructor(private val appContext: Context, initialStore: SharedPreferences) {

    constructor(context: Context) : this(context.applicationContext, shared(context.applicationContext))

    // Always the CURRENT shared store: when a sign-in recovers the on-disk file, every live
    // CredStore - this one included - switches to it, so no later sign-out or token write can
    // land in a dead in-memory store while the file has the newer snapshot.
    private val prefs: SharedPreferences get() = shared(appContext)

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

    private val _needsLogin = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("needsLogin", false))

    /** Observable session-expired flag: the open screen reacts the moment the worker sets it. */
    val needsLoginFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _needsLogin

    var needsLogin: Boolean
        get() = prefs.getBoolean("needsLogin", false)
        set(v) {
            prefs.edit().putBoolean("needsLogin", v).apply()
            _needsLogin.value = v
        }

    var loginNotified: Boolean
        get() = prefs.getBoolean("loginNotified", false)
        set(v) = prefs.edit().putBoolean("loginNotified", v).apply()

    /** True after he chose Sign out: queued shares wait quietly, with no sign-in nag. */
    var signedOut: Boolean
        get() = prefs.getBoolean("signedOut", false)
        set(v) = prefs.edit().putBoolean("signedOut", v).apply()

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
            // Posted notices belong to the old account: cancel them before the wipe, or one
            // tap would open the new account with the old account's warning.
            val q = QueueStore(appContext)
            q.shareQueue().forEach { Notifier.clearRedirectNotice(appContext, it.url) }
            q.skipped().forEach { Notifier.cancelSkipped(appContext, it.url); Notifier.clearRedirectNotice(appContext, it.url) }
            q.clearAll()
        }
        val loginAt = System.currentTimeMillis()
        e.putLogin(server, username, token, password, remember, loginAt)
        e.apply()
        _needsLogin.value = false
        if (prefs is MemoryPrefs) {
            // The saved file could not be read at launch and was left untouched. Fresh
            // credentials are in hand now, so replacing it loses nothing. The shape is
            // dictated by EncryptedSharedPreferences: the logical file name is bound into
            // the encrypted keys (AAD), so a renamed-in staged file would not decrypt. The
            // replacement is created under the final name from the start; the ORIGINAL
            // bytes are moved aside (never deleted) and moved back if the recreate fails.
            // Crash mid-swap: the aside copy still holds the original bytes and the next
            // open simply falls back to memory again.
            val dir = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs")
            val old = java.io.File(dir, "creds.xml")
            // Every preserved generation keeps its own name: an earlier aside may be the only
            // copy of the original bytes after a crash, so one is never overwritten.
            var aside = java.io.File(dir, "creds.unreadable.xml")
            var gen = 2
            while (aside.exists()) { aside = java.io.File(dir, "creds.unreadable.$gen.xml"); gen++ }
            // The framework's own crashed-write backup (creds.xml.bak) is preserved with its
            // generation: SharedPreferencesImpl.loadFromDisk RESURRECTS a leftover .bak over
            // the fresh file (it deletes creds.xml and renames the .bak into place), and
            // deleteSharedPreferences deletes the .bak outright - both would destroy the one
            // other copy of the original bytes. The .bak moves FIRST: a crash after it but
            // before the main file leaves the unreadable file in place with no backup to
            // resurrect, so the next open simply falls back to memory again.
            val oldBak = java.io.File(dir, "creds.xml.bak")
            val asideBak = java.io.File(dir, aside.name + ".bak")
            try {
                if (oldBak.isFile) check(oldBak.renameTo(asideBak)) { "could not move the crashed-write backup aside" }
                if (old.isFile) check(old.renameTo(aside)) { "could not move the unreadable file aside" }
                // Evict the framework's File-keyed SharedPreferences cache entry: it can still
                // hold the undecryptable instance, and a fresh create under the same name would
                // reuse it. Both files are already aside, so only the cached instance is dropped.
                appContext.deleteSharedPreferences("creds")
                try {
                    val freshPrefs = create(appContext)
                    val fresh = freshPrefs.edit().putLogin(server, username, token, password, remember, loginAt)
                    check(fresh.commit()) { "fresh login did not land on disk" }
                    instance = freshPrefs // every live CredStore follows this store now
                } catch (e: Exception) {
                    // Recreate failed: drop the partial fresh file AND its cache entry, put the
                    // original bytes back (backup first, so its restore cannot be clobbered by
                    // a partial fresh write), stay memory-only.
                    appContext.deleteSharedPreferences("creds")
                    if (asideBak.isFile && !oldBak.isFile) asideBak.renameTo(oldBak)
                    if (aside.isFile && !old.isFile) aside.renameTo(old)
                }
            } catch (e: Exception) {
                // Could not even move the file aside: nothing was changed, stay memory-only.
            }
        }
    }

    /** The credential field set, written identically to the live store and to a recreated
     *  file after an unreadable one: one helper, so a future field can't land in one and
     *  not the other. */
    private fun SharedPreferences.Editor.putLogin(server: String, username: String, token: String, password: String, remember: Boolean, loginAt: Long): SharedPreferences.Editor {
        putString("server", server)
        putString("username", username)
        putString("token", token)
        putLong("loginAt", loginAt)
        putBoolean("needsLogin", false)
        putBoolean("loginNotified", false)
        putBoolean("signedOut", false)
        putBoolean("rememberMe", remember)
        if (remember) putString("password", password) else remove("password")
        return this
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
            .putBoolean("signedOut", true)
            .apply()
        _needsLogin.value = false
    }

    companion object {
        @Volatile private var instance: SharedPreferences? = null

        private fun shared(ctx: Context): SharedPreferences =
            instance ?: synchronized(this) { instance ?: open(ctx).also { instance = it } }

        /** Test hook: drop the cached store so the next access reopens from disk. */
        internal fun resetForTests() = synchronized(this) { instance = null }

        /** Build the shared instance off the main thread during startup. */
        fun warm(ctx: Context) {
            shared(ctx.applicationContext)
        }

        /** Test hook: slow the one-time migration so a mid-migration reader can be exercised. */
        @Volatile var migrationSlowdownMs: Long = 0L

        /** Test hook: make the next create() throw, to exercise the rollback path. */
        @Volatile internal var failNextCreateForTests = false

        /**
         * Test hook: replace the encrypted-store factory. Robolectric has no
         * AndroidKeyStore, so the real factory can never succeed there; the override
         * lets the recovery branches run against a file-backed plain store.
         */
        @Volatile internal var createOverrideForTests: ((Context) -> SharedPreferences)? = null

        private fun create(ctx: Context): SharedPreferences {
            if (failNextCreateForTests) { failNextCreateForTests = false; throw java.security.GeneralSecurityException("injected create failure") }
            createOverrideForTests?.let { return it(ctx) }
            return doCreate(ctx)
        }

        private fun doCreate(ctx: Context): SharedPreferences = EncryptedSharedPreferences.create(
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
                // fall through to the fallback
            }
            // Two failures in this launch. The file is PRESERVED, always: neither a retry
            // count nor a load-only Keystore probe can prove a login file unrecoverable
            // (locked Keystore, struggling Keystore, transient decryption fault), and a
            // wrong call would delete a login that reads fine later. Run signed out in
            // memory, ask him to sign in, and never crash. If he does sign in, THAT is the
            // moment replacing the file loses nothing - see markLoggedIn.
            // Publish the in-memory store BEFORE anything can resolve prefs through
            // shared(): constructing a CredStore here (or touching its prefs) would
            // re-enter open() and recurse forever - the fallback must never look back.
            val mem = MemoryPrefs()
            instance = mem
            Notifier.notifySignInOnce(ctx, CredStore(ctx, mem))
            return mem
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
            // The Keystore-down fallback reads as empty; marking migration done from it
            // would strand legacy data. Leave the marker for a launch that can really read.
            if (enc is MemoryPrefs) return
            if (migrationSlowdownMs > 0) Thread.sleep(migrationSlowdownMs) // tests only; 0 in production
            // Reads first: a failure here writes nothing, and the next launch retries.
            val queue = enc.getString("queue", null)
            val skippedUpdate = enc.getString("skipped_update", null)
            // Then one atomic write for the import and the marker together - no window
            // where imported data exists without the marker (see QueueStore).
            val durable = plain.importQueueAndMarkMigrated(queue, skippedUpdate)
            if (durable) {
                try {
                    enc.edit().remove("queue").remove("attempts").remove("skipped_update").apply()
                } catch (e: Exception) {
                    // Stale legacy values are inert: the migrated marker is the only guard.
                }
            }
            // When the durable write is unconfirmed the legacy source stays in place;
            // the import merges, so the next launch's retry loses nothing.
            // The old queue-wide retry counter is deliberately not carried over: retries
            // are per link now, and a stale count would only mis-delay one share.
        }
    }
}

/**
 * Last-resort preferences when the Keystore is down: everything reads as empty,
 * writes live only until the process ends. The app behaves signed out and never crashes.
 */
private class MemoryPrefs : SharedPreferences {
    private val map = java.util.Collections.synchronizedMap(HashMap<String, Any?>())

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
