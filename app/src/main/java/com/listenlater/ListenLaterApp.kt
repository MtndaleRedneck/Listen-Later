package com.listenlater

import android.app.Application

class ListenLaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }
}
