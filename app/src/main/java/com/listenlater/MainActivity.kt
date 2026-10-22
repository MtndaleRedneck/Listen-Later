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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    private lateinit var store: CredStore

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CredStore(this)

        // CI screenshots: seed canned demo state (debug builds only).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) {
            Demo.active = true
            Demo.forceLoadFail = intent.getBooleanExtra("loadfail", false)
            if (intent.getBooleanExtra("signedout", false)) Demo.seedSignedOut(this)
            else Demo.seedSignedIn(this)
            if (intent.getBooleanExtra("empty", false)) QueueStore(this).clearAll()
            if (intent.getBooleanExtra("titleold", false)) TitleOld = true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ListenLaterTheme {
                var screen by rememberSaveable {
                    mutableStateOf(
                        if (store.token != null && !store.needsLogin) "status" else "signin"
                    )
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    if (screen == "signin") {
                        SignInScreen(store = store, onLoggedIn = {
                            if (QueueStore(this@MainActivity).shareQueue().isNotEmpty()) {
                                ShareWorker.enqueue(this@MainActivity, 0L)
                            }
                            screen = "status"
                        })
                    } else {
                        StatusScreen(
                            store = store,
                            onSignedOut = {
                                store.signOut()
                                screen = "signin"
                            },
                            onSessionExpired = {
                                store.needsLogin = true
                                screen = "signin"
                            }
                        )
                    }
                }
            }
        }
    }
}
