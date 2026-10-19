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

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ListenLaterTheme {
                var screen by remember {
                    mutableStateOf(
                        if (store.token != null && !store.needsLogin) "status" else "signin"
                    )
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    if (screen == "signin") {
                        SignInScreen(store = store, onLoggedIn = {
                            if (store.shareQueue().isNotEmpty()) {
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
