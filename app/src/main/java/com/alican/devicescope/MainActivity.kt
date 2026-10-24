package com.alican.devicescope

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.alican.devicescope.bench.BenchController
import com.alican.devicescope.ui.AppState
import com.alican.devicescope.ui.DeviceScopeApp

class MainActivity : ComponentActivity() {

    private lateinit var appState: AppState
    private lateinit var bench: BenchController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appState = AppState(applicationContext)
        bench = BenchController(applicationContext)

        // Cold start: process creation -> first frame. Recorded once per process.
        LaunchMetrics.record()
        android.view.Choreographer.getInstance().postFrameCallback {
            LaunchMetrics.markFirstFrame()
        }

        setContent {
            ApplySystemBars(dark = appState.dark)
            DeviceScopeApp(appState, bench)
        }

        askNotificationPermission()
    }

    @Composable
    private fun ApplySystemBars(dark: Boolean) {
        val bg = if (dark) Color(0xFF14181E) else Color(0xFFE6EBF2)
        window.statusBarColor = bg.toArgb()
        window.navigationBarColor = bg.toArgb()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
