package com.fortis.wallet

import android.app.Application

class FortisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install()
    }
}
