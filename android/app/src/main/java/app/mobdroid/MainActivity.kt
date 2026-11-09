package app.mobdroid

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mobdroid.ui.MainScreen
import app.mobdroid.ui.MobdroidTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as App

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) = updateRotation()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) app.engine.startPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.settings.settings.map { it.keepScreenOn }.distinctUntilChanged()
                    .collect { keepOn ->
                        if (keepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
            }
        }
        setContent { MobdroidTheme { MainScreen(app.engine, app.settings) } }
        if (!hasPermissions()) permissionLauncher.launch(PERMISSIONS)
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        updateRotation()
        if (hasPermissions()) app.engine.startPreview()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        app.engine.stopPreviewIfIdle()
        super.onStop()
    }

    private fun updateRotation() {
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= 30) display.rotation else windowManager.defaultDisplay.rotation
        app.engine.setDisplayRotation(rotation * 90)
    }

    private fun hasPermissions() = REQUIRED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private companion object {
        val REQUIRED = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val PERMISSIONS = if (Build.VERSION.SDK_INT >= 33) REQUIRED + Manifest.permission.POST_NOTIFICATIONS else REQUIRED
    }
}
