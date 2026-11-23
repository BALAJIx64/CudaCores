package com.guardiangrid

import android.app.Application
import android.util.Log

class GuardianGridApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GuardianGrid Application initialized.")
    }

    companion object {
        private const val TAG = "GuardianGridApp"
    }
}
