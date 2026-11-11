package com.opirl.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.opirl.app.ui.Wizard
import com.opirl.app.ui.rememberAccess
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.opirl.app.ui.MainScreen
import com.opirl.app.ui.OpirlTheme
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

    private var portrait = false
    private var fold by mutableStateOf<FoldingFeature?>(null)

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                app.settings.settings.map { it.selectedProfile.portrait }.distinctUntilChanged().collect {
                    portrait = it
                    updateOrientation()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.state == FoldingFeature.State.HALF_OPENED }
                    updateOrientation()
                }
            }
        }
        portrait = app.settings.settings.value.selectedProfile.portrait
        updateOrientation()
        setContent {
            OpirlTheme {
                val settings by app.settings.settings.collectAsStateWithLifecycle()
                val access = rememberAccess { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) app.engine.startPreview() }
                val motion = MaterialTheme.motionScheme
                MainScreen(
                    app.engine,
                    app.settings,
                    access,
                    fold?.takeIf { it.orientation == FoldingFeature.Orientation.HORIZONTAL }?.bounds,
                )
                AnimatedVisibility(
                    !settings.setupDone,
                    enter = fadeIn(motion.defaultEffectsSpec()),
                    exit = fadeOut(motion.defaultEffectsSpec()),
                ) { Wizard(app.settings, access) }
            }
        }
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

    private fun updateOrientation() {
        val hinge = fold
        val foldsLikeAFlip = hinge != null &&
            (hinge.orientation == FoldingFeature.Orientation.HORIZONTAL) != (currentRotation() % 180 != 0)
        requestedOrientation = when {
            foldsLikeAFlip -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun currentRotation(): Int {
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= 30) display.rotation else windowManager.defaultDisplay.rotation
        return rotation * 90
    }

    private fun updateRotation() = app.engine.setDisplayRotation(currentRotation())

    private fun hasPermissions() = REQUIRED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private companion object {
        val REQUIRED = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
