package app.mobdroid

import android.app.Application
import app.mobdroid.settings.SettingsStore
import app.mobdroid.stream.StreamEngine
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
