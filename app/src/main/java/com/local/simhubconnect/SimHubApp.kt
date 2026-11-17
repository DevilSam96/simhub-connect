package com.local.simhubconnect

import android.app.Application
import com.local.simhubconnect.settings.AppSettings

/** Applies the saved light/dark preference before any activity is created. */
class SimHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.applyNightMode(this)
    }
}
