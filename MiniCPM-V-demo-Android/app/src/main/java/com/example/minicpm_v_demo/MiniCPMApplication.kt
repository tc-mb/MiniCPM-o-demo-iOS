package com.example.minicpm_v_demo

import android.app.Application

class MiniCPMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleManager.applyOnAppStart(this)
    }
}
