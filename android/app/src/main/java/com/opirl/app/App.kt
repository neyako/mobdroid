package com.opirl.app

import android.app.Application
import com.opirl.app.settings.SettingsStore
import com.opirl.app.stream.StreamEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var engine: StreamEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(File(filesDir, "settings.json"), CoroutineScope(SupervisorJob()))
        engine = StreamEngine(this, settings)
    }
}
