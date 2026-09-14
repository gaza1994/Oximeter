package com.garethjohnstone.oximeter.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Why a scan might be returning nothing. All three of these fail silently on
 * Android - startScan() just never calls back - so they have to be checked and
 * shown rather than inferred.
 */
data class SystemStatus(
    val bluetoothOn: Boolean,
    val permissionsGranted: Boolean,
    val missingPermissions: List<String>,
    val locationServicesOn: Boolean,
    val locationMatters: Boolean
) {
    val allClear: Boolean
        get() = bluetoothOn && permissionsGranted && (!locationMatters || locationServicesOn)
}

fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    // Requested on every version: several manufacturers still return no scan
    // results without it, regardless of what the platform documents.
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun readSystemStatus(context: Context): SystemStatus {
    val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
    val missing = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    val lm = context.getSystemService(LocationManager::class.java)
    val locationOn = when {
        lm == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> lm.isLocationEnabled
        else -> lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    return SystemStatus(
        bluetoothOn = bt?.isEnabled == true,
        permissionsGranted = missing.isEmpty(),
        missingPermissions = missing,
        locationServicesOn = locationOn,
        locationMatters = true
    )
}

/** Re-reads every couple of seconds, so toggling a setting updates the screen. */
@Composable
fun rememberSystemStatus(): SystemStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readSystemStatus(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            status = readSystemStatus(context)
            delay(2_000)
        }
    }
    return status
}

fun openBluetoothSettings(context: Context) =
    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun openLocationSettings(context: Context) =
    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun openAppSettings(context: Context) = context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)
