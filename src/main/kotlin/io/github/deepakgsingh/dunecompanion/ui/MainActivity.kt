package io.github.deepakgsingh.dunecompanion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.deepakgsingh.dunecompanion.Steward
import io.github.deepakgsingh.dunecompanion.service.StewardService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startSteward() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // The foreground service's notification needs this on Android 13+. The service still
        // runs if it is refused; the notification just does not show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSteward()
        }

        // Keep the screen on only while a game is running. This is somebody's phone, not a
        // kiosk: between games it should sleep like any other app.
        lifecycleScope.launch {
            Steward.dune.collect { session ->
                if (session.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Closing from the display or the notification: stop the service, then leave, and
        // take the task out of recents so it does not look like it is still running.
        lifecycleScope.launch {
            Steward.quit.collect {
                stopService(Intent(this@MainActivity, StewardService::class.java))
                finishAndRemoveTask()
            }
        }

        setContent { DisplayScreen() }
    }

    private fun startSteward() {
        startForegroundService(Intent(this, StewardService::class.java))
    }
}
