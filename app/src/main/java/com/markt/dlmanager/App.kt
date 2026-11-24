package com.markt.dlmanager

import android.app.Application
import com.markt.dlmanager.engine.Downloads

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Downloads.init(this)
    }
}
