package com.zack694.modinj

import android.app.Application
import com.google.android.material.color.DynamicColors

class ModInjApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: adopt the system palette on Android 12+ (falls back to
        // the built-in emerald scheme on older devices).
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Discover the launcher bridge once at startup (cheap ping).
        Thread { ModSyncClient.ping(this) }.start()
    }
}
