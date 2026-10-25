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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.widget.Toast
import kotlinx.coroutines.delay

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
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("needslogin", false)) {
                        store!!.needsLogin = true
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
                        var screen by rememberSaveable {
                            mutableStateOf(
                                if (s.token != null && (!s.needsLogin || stayStatus)) "status" else "signin"
                            )
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
