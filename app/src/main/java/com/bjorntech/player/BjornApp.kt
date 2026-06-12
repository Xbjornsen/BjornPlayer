package com.bjorntech.player

import android.app.Application

/** Applies the saved theme before any Activity is created. */
class BjornApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsManager.applyTheme(this)
    }
}
