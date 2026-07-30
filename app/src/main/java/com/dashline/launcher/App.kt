package com.dashline.launcher

import android.app.Application
import android.content.Context

/** Applies the saved language + theme as early as possible, before any activity. */
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.applyTo(base))
    }

    override fun onCreate() {
        super.onCreate()
        ThemeManager.apply(Prefs(this))
    }
}
