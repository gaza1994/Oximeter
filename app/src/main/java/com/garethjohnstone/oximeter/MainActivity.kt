package com.garethjohnstone.oximeter

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.garethjohnstone.oximeter.monitor.MonitorViewModel
import com.garethjohnstone.oximeter.ui.MonitorScreen
import com.garethjohnstone.oximeter.ui.OximeterTheme
import com.garethjohnstone.oximeter.ui.Diagnostics
import com.garethjohnstone.oximeter.ui.SettingsScreen
import com.garethjohnstone.oximeter.ui.rememberSystemStatus
import com.garethjohnstone.oximeter.ui.requiredPermissions

class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val prefsForTheme by vm.prefs.collectAsState()
            val themeId = prefsForTheme.themeId
            OximeterTheme(themeId = themeId) {
                val state by vm.ui.collectAsState()
                val prefs by vm.prefs.collectAsState()
                val status = rememberSystemStatus()

                // Start scanning as soon as the blockers clear.
                LaunchedEffect(status.allClear) {
                    if (status.allClear) vm.start()
                }

                // Screen behaviour follows settings.
                LaunchedEffect(prefs.keepScreenOn, prefs.dimDisplay) {
                    if (prefs.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (prefs.dimDisplay) 0.05f else -1f
                    }
                }

                if (state.showSettings) {
                    BackHandler { vm.closeSettings() }
                    SettingsScreen(
                        prefs = prefs,
                        onChange = vm::updatePrefs,
                        onReset = vm::resetPrefs,
                        onForgetDevice = vm::forgetDevice,
                        onPreviewAlarm = vm::previewAlarm,
                        onPreviewPulse = vm::previewPulse,
                        devices = state.devices,
                        alarmHistory = state.alarmHistory,
                        onClearHistory = vm::clearHistory,
                        onPickDevice = vm::pickDevice,
                        onRescan = vm::rescan,
                        diagnostics = Diagnostics(
                            framesPerSecond = state.framesPerSecond,
                            badChecksums = state.badChecksums,
                            deviceType = state.deviceType,
                            connected = state.link == com.garethjohnstone.oximeter.ble.LinkState.Connected
                        ),
                        onClose = vm::closeSettings
                    )
                } else {
                    MonitorScreen(
                        state = state,
                        status = status,
                        spo2Low = prefs.spo2Low,
                        onToggleMute = vm::toggleMute,
                        onOpenSettings = vm::openSettings,
                        onGrantPermissions = ::askForPermissions
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        askForPermissions()
    }

    private fun askForPermissions() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        // Starting is left to the composable, which watches the same status and
        // would otherwise start a second scan a moment later. Every extra
        // startScan eats into the platform's 5-per-30-seconds quota.
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onStop() {
        super.onStop()
        // Release the sensor as soon as the app loses focus. Holding the link
        // open in the background leaves the sensor unavailable to anything else
        // and risks a stale connection surviving the process. Reconnecting is
        // cheap now that the address is remembered and connected to directly.
        vm.stop()
    }

}
