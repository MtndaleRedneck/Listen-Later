package com.listenlater

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.widget.Toast
import kotlinx.coroutines.delay

/** Process-scoped: the startup queue nudge fires once per process (see the LaunchedEffect
 *  below); a process death re-arms it. */
private var queueNudged = false

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ListenLaterTheme {
                // Storage readies off the opening path: until the startup gate
                // completes, the app's plain background shows - no logo, no spinner.
                var store by remember { mutableStateOf<CredStore?>(null) }
                LaunchedEffect(Unit) {
                    (application as ListenLaterApp).ready.await()
                    // CI screenshots: seed canned demo state (debug builds only).
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) {
                        Demo.active = true
                        Demo.forceLoadFail = intent.getBooleanExtra("loadfail", false)
                        if (intent.getBooleanExtra("signedout", false)) Demo.seedSignedOut(this@MainActivity)
                        else Demo.seedSignedIn(this@MainActivity)
                        if (intent.getBooleanExtra("queuestates", false)) Demo.seedQueueStates(this@MainActivity)
                        if (intent.getBooleanExtra("empty", false)) QueueStore(this@MainActivity).clearAll()
                    }
                    // CI screenshot of the startup background: hold the gate a moment.
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("holdgate", false)) delay(2500)
                    store = CredStore(this@MainActivity)
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) {
                        store!!.statusCentered = intent.getBooleanExtra("centered", false)
                    }
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("needslogin", false)) {
                        store!!.needsLogin = true
                    }
                    // CI proof only (debug builds): posts the app's real notifications so the small icon can be
                    // captured in the status bar and the shade. Runs only after the demo seed (Demo.active), which replaces the
                    // stored sign-in and queue with canned demo data. A clean CI install is what keeps real data out of it. Uses the production Notifier, no copy of it.
                    if (BuildConfig.DEBUG && Demo.active && intent.getBooleanExtra("postnotice", false)) {
                        store!!.loginNotified = false
                        Notifier.notifySignInOnce(this@MainActivity, store!!)
                        Notifier.skipped(this@MainActivity, "https://example.invalid/demo", "A demo video", "This video is members-only.")
                    }
                    // CI screenshot of a real send toast, with the worker's own wording.
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("toast", false)) {
                        Toast.makeText(
                            this@MainActivity,
                            ShareLogic.decide(ShareLogic.Outcome.SENT, 0, "The demo video title").toast,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    val s = store
                    if (s != null) {
                        val stayStatus = BuildConfig.DEBUG && intent.getBooleanExtra("staystatus", false)
                        val needsLogin by s.needsLoginFlow.collectAsState()
                        var screen by rememberSaveable {
                            mutableStateOf(
                                if (s.token != null && (!needsLogin || stayStatus)) "status" else "signin"
                            )
                        }
                        // Observable: the moment the worker marks the session expired, any
                        // screen (including a restored "status") drops to sign-in.
                        LaunchedEffect(needsLogin) {
                            if (needsLogin && screen != "signin") screen = "signin"
                        }
                        // A share whose process died between its durable queue write and the
                        // worker kick is still on disk; nudge the queue once PER PROCESS,
                        // not on every recreation - a re-nudge REPLACEs and cancels an
                        // in-flight send. Process death re-arms the flag, which is exactly
                        // when a killed share still needs the kick.
                        LaunchedEffect(Unit) {
                            if (!queueNudged && QueueStore(this@MainActivity).shareQueue().isNotEmpty()) {
                                queueNudged = true
                                ShareWorker.enqueue(this@MainActivity, 0L)
                            }
                        }
                        if (screen == "signin") {
                            SignInScreen(store = s, onLoggedIn = {
                                if (QueueStore(this@MainActivity).shareQueue().isNotEmpty()) {
                                    ShareWorker.enqueue(this@MainActivity, 0L)
                                }
                                screen = "status"
                            })
                        } else {
                            StatusScreen(
                                store = s,
                                onSignedOut = {
                                    s.signOut()
                                    screen = "signin"
                                },
                                onSessionExpired = {
                                    s.needsLogin = true
                                    screen = "signin"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
