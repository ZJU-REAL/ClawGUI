package com.clawgui.ng

import android.app.Application
import com.clawgui.ng.runtime.RuntimeContainer

class ClawNgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        RuntimeContainer.init(this)
    }

    companion object {
        lateinit var instance: ClawNgApp
            private set
    }
}
