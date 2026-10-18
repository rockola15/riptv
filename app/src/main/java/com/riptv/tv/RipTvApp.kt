package com.riptv.tv

import android.app.Application

class RipTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }
}
