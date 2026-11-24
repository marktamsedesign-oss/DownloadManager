package com.markt.dlmanager

import android.app.Application
import com.markt.dlmanager.browser.BrowserPrefs
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.update.Updater

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Downloads.init(this)
        BrowserPrefs.init(this)
        Updater.init(this)
    }
}
